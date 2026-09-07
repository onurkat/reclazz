/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.reload.BatchOrder;
import com.onurkat.reclazz.ui.Plural;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Supervised;
import com.onurkat.reclazz.watcher.ChangeEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * The one thread reloads run on, and what is queued for it.
 *
 * <p>Single-threaded, and that is a correctness requirement rather than a
 * resource decision. A reload does not only redefine a class: it clears and
 * refills framework state that is shared across the whole application,
 * injection metadata, mapping registries, the validator's constraint caches,
 * the security metadata. None of that is written to be entered twice at once.
 * Serialising batches here is what lets every one of those reloaders be
 * written as though it were the only thing running.
 *
 * <p>Around that thread, three things that belong together and used to be
 * spread through the agent's start-up: every piece of work is supervised, so
 * a failure is a sentence rather than a silent future, and timed, so a hang
 * is one too ({@link ReloadStall}); and class files that land together are
 * coalesced into one batch, given a moment to finish arriving, ordered
 * callees first and reloaded inside one bracket. What is done per event is
 * the {@link Handler}'s business; what a bracket means is the
 * {@link Bracket}'s.
 */
public final class ReloadQueue {

    /** What one change event gets done to it. */
    public interface Handler {
        void handle(ChangeEvent event);
    }

    /** What surrounds a batch of class files: opened before the first, closed after the last. */
    public interface Bracket {
        void begin();

        void end();
    }

    /** How the batch waits for stragglers; a test hands in one that does not sleep. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** A reload still running after this long is reported, once. */
    static final long STALL_WARN_MS = 30_000;
    static final long STALL_CHECK_MS = 5_000;
    /** How long a burst is given to finish arriving once it has been seen to be one. */
    static final long BATCH_GRACE_MS = 100;
    static final long BATCH_MAX_WAIT_MS = 1000;

    private final Handler handler;
    private final Bracket bracket;
    private final Executor executor;
    private final ReloadStall stall;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final LinkedHashMap<Path, ChangeEvent> pendingClassFiles = new LinkedHashMap<>();
    private volatile boolean running = true;
    private ExecutorService ownedExecutor;

    /** The production queue: its own daemon thread, a real clock, real sleeps, and the stall watch started. */
    public static ReloadQueue start(Handler handler, Bracket bracket) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Reclazz-Reloader");
            t.setDaemon(true);
            return t;
        });
        ReloadQueue queue = new ReloadQueue(handler, bracket, executor,
                new ReloadStall(System::currentTimeMillis, STALL_WARN_MS),
                System::currentTimeMillis, Thread::sleep);
        queue.ownedExecutor = executor;
        queue.startStallWatch();
        return queue;
    }

    ReloadQueue(Handler handler, Bracket bracket, Executor executor, ReloadStall stall,
                LongSupplier clock, Sleeper sleeper) {
        this.handler = handler;
        this.bracket = bracket;
        this.executor = executor;
        this.stall = stall;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * One thread's worth of saying so when a reload never comes back: the
     * reloads behind it are queued, and a queue is silent.
     */
    private void startStallWatch() {
        Thread watch = new Thread(Supervised.forever(
                "The reload watch",
                "A reload that hangs will no longer be reported. Reloading itself is unaffected.",
                () -> {
                    while (running) {
                        try {
                            Thread.sleep(STALL_CHECK_MS);
                        } catch (InterruptedException interrupted) {
                            return;
                        }
                        stall.check();
                    }
                }), "Reclazz-ReloadWatch");
        watch.setDaemon(true);
        watch.start();
    }

    /** Queue one piece of work: supervised, timed, on the reload thread. */
    public void submit(String what, Runnable work) {
        executor.execute(Supervised.once(what, stall.timed(what, work)));
    }

    /**
     * A class file that has landed. Held until {@link #submitClassBatch} so
     * the files of one save reach the thread as one task.
     */
    public void enqueueClassFile(ChangeEvent event) {
        synchronized (pendingClassFiles) {
            pendingClassFiles.put(event.getPath(), event);
        }
    }

    /** Queue the task that reloads whatever class files have been enqueued. */
    public void submitClassBatch(int enqueuedNow) {
        submit("Reloading " + Plural.of(enqueuedNow, "class file"), this::reloadClassBatch);
    }

    /** The HEALTH line for a reload that is holding everything up, or null. */
    public String healthLine() {
        return stall.healthLine();
    }

    /** Stop the thread and the watch; nothing queued runs after this. */
    public void shutdown() {
        running = false;
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }

    /**
     * Reload every class file that has landed, as one batch.
     *
     * <p>Each class still goes through the handler, so what is done and said
     * per class is unchanged. What the batch adds is the bracket around them.
     * A single class is handed straight through, with no bracket and no
     * waiting, so the latency of an ordinary save is what it was.
     *
     * <p>The first file of a burst can reach this thread before the rest
     * have been queued. Once two or more have been seen the burst is real,
     * and it is given a short while to finish arriving rather than being
     * split into a batch and a tail of singles, each with its own sweep.
     */
    void reloadClassBatch() {
        List<ChangeEvent> batch = drainClassFiles();
        if (batch.isEmpty()) return;          // drained by an earlier task

        if (batch.size() > 1) {
            long deadline = clock.getAsLong() + BATCH_MAX_WAIT_MS;
            while (clock.getAsLong() < deadline) {
                try {
                    sleeper.sleep(BATCH_GRACE_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                List<ChangeEvent> more = drainClassFiles();
                if (more.isEmpty()) break;
                batch.addAll(more);
            }
        }

        if (batch.size() == 1) {
            handler.handle(batch.get(0));
            return;
        }

        StatusReporter.info(batch.size() + " class files changed together; reloading them as one batch");
        long startTime = clock.getAsLong();
        // Callees before callers, so no caller's new body reaches a callee
        // that still has its old shape. See BatchOrder.
        List<ChangeEvent> ordered = BatchOrder.calleesFirst(batch);
        bracket.begin();
        try {
            for (ChangeEvent event : ordered) {
                handler.handle(event);
            }
        } finally {
            bracket.end();
        }
        StatusReporter.info("Batch of " + batch.size() + " class files done ("
                + (clock.getAsLong() - startTime) + "ms)");
    }

    private List<ChangeEvent> drainClassFiles() {
        synchronized (pendingClassFiles) {
            if (pendingClassFiles.isEmpty()) return new ArrayList<>();
            List<ChangeEvent> drained = new ArrayList<>(pendingClassFiles.values());
            pendingClassFiles.clear();
            return drained;
        }
    }
}
