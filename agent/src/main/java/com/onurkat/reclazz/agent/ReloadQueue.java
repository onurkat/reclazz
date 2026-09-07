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
import java.nio.file.Files;
import java.io.IOException;
import com.onurkat.reclazz.ui.Failures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

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

    interface ByteReader { byte[] read(Path path) throws IOException; }
    private final Object buildLock = new Object();
    private long generation;
    private boolean holding;
    private long heldSince;
    private boolean holdWarned;
    private final ByteReader reader;
    static final long BUILD_WARN_MS = 300_000;

    private final Handler handler;
    private final Bracket bracket;
    private final Executor executor;
    private final ReloadStall stall;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final LinkedHashMap<Path, ChangeEvent> pendingClassFiles = new LinkedHashMap<>();
    private volatile boolean running = true;
    private ExecutorService ownedExecutor;
    private Consumer<Runnable> classBoundary = Runnable::run;

    /** The production queue: its own daemon thread, a real clock, real sleeps, and the stall watch started. */
    public static ReloadQueue start(Handler handler, Bracket bracket) {
        return start(handler, bracket, Runnable::run);
    }

    public static ReloadQueue start(Handler handler, Bracket bracket, Consumer<Runnable> classBoundary) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Reclazz-Reloader");
            t.setDaemon(true);
            return t;
        });
        ReloadQueue queue = new ReloadQueue(handler, bracket, executor,
                new ReloadStall(System::currentTimeMillis, STALL_WARN_MS),
                System::currentTimeMillis, Thread::sleep);
        queue.ownedExecutor = executor;
        queue.classBoundary = classBoundary;
        queue.startStallWatch();
        return queue;
    }

    ReloadQueue(Handler handler, Bracket bracket, Executor executor, ReloadStall stall,
                LongSupplier clock, Sleeper sleeper) {
        this(handler, bracket, executor, stall, clock, sleeper, Files::readAllBytes);
    }

    ReloadQueue(Handler handler, Bracket bracket, Executor executor, ReloadStall stall,
                LongSupplier clock, Sleeper sleeper, ByteReader reader) {
        this.reader = reader;
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
                        checkBuildHold();
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
        synchronized (buildLock) {
            pendingClassFiles.put(event.getPath(), event);
        }
    }

    /** Queue the task that reloads whatever class files have been enqueued. */
    public void submitClassBatch(int enqueuedNow) {
        synchronized (buildLock) { if (holding) return; }
        submit("Reloading " + Plural.of(enqueuedNow, "class file"), this::reloadClassBatch);
    }

    /** The HEALTH line for a reload that is holding everything up, or null. */
    public String healthLine() {
        synchronized (buildLock) {
            String reload = stall.healthLine();
            if (!holding) return reload;
            return "Build holding " + pendingClassFiles.size() + " class files since "
                    + java.time.Instant.ofEpochMilli(heldSince) + "; waiting for BUILD ok"
                    + (reload == null ? "" : ". " + reload);
        }
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
    /** Socket-thread state changes must not wait for the reload executor. */
    public void build(String state, Runnable scan) {
        long accepted;
        synchronized (buildLock) {
            if (state.equalsIgnoreCase("started") || state.equalsIgnoreCase("failed")) {
                generation++;
                if (!holding || state.equalsIgnoreCase("started")) {
                    heldSince = clock.getAsLong();
                    holdWarned = false;
                }
                holding = true;
                StatusReporter.info("Build " + state.toLowerCase(java.util.Locale.ROOT) + "; holding "
                        + pendingClassFiles.size() + " class files until BUILD ok");
                return;
            }
            if (!state.equalsIgnoreCase("ok")) return;
            accepted = holding ? generation : -1;
        }
        if (accepted == -1) submit("Scanning build output", scan);
        else submit("Accepting build output", () -> acceptBuild(accepted, scan));
    }

    void checkBuildHold() {
        synchronized (buildLock) {
            if (holding && !holdWarned && clock.getAsLong() - heldSince >= BUILD_WARN_MS) {
                holdWarned = true;
                StatusReporter.warn("Build result missing after five minutes; " + healthLine());
            }
        }
    }

    private void acceptBuild(long accepted, Runnable scan) {
        synchronized (buildLock) {
            if (generation != accepted || !holding) return;
        }
        // Never hold buildLock while the watcher delivers its scan into this queue.
        scan.run();
        List<ChangeEvent> batch;
        synchronized (buildLock) {
            if (generation != accepted || !holding) {
                StatusReporter.info("A later build superseded this success; files remain held");
                return;
            }
            batch = drainClassFiles();
        }
        List<ChangeEvent> capture = capture(batch, true);
        synchronized (buildLock) {
            if (capture == null || generation != accepted || !holding) {
                restore(batch);
                return;
            }
            holding = false;
        }
        if (!capture.isEmpty()) classBoundary.accept(() -> applyClassBatch(capture));
    }

    void reloadClassBatch() {
        List<ChangeEvent> batch;
        long accepted;
        synchronized (buildLock) {
            if (holding) return;
            accepted = generation;
            batch = drainClassFiles();
        }
        if (batch.isEmpty()) return;
        if (batch.size() > 1) {
            long deadline = clock.getAsLong() + BATCH_MAX_WAIT_MS;
            while (clock.getAsLong() < deadline) {
                try {
                    sleeper.sleep(BATCH_GRACE_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                synchronized (buildLock) {
                    if (holding || generation != accepted) break;
                    List<ChangeEvent> more = drainClassFiles();
                    if (more.isEmpty()) break;
                    batch.addAll(more);
                }
            }
        }
        // A path touched twice during the grace period has one latest event.
        LinkedHashMap<Path, ChangeEvent> latest = new LinkedHashMap<>();
        for (ChangeEvent event : batch) latest.put(event.getPath(), event);
        batch = new ArrayList<>(latest.values());
        List<ChangeEvent> capture = capture(batch, false);
        synchronized (buildLock) {
            if (holding || generation != accepted) {
                restore(batch);
                return;
            }
        }
        if (!capture.isEmpty()) classBoundary.accept(() -> applyClassBatch(capture));
    }

    private List<ChangeEvent> capture(List<ChangeEvent> batch, boolean whole) {
        List<ChangeEvent> capture = new ArrayList<>();
        for (ChangeEvent event : batch) {
            try {
                byte[] bytes = event.getBytes();
                if (bytes == null) bytes = reader.read(event.getPath());
                capture.add(event.withBytes(bytes));
            } catch (IOException failure) {
                StatusReporter.error("Failed to read class file " + event.getPath() + ": " + Failures.describe(failure));
                if (whole) return null;
            }
        }
        return List.copyOf(capture);
    }

    private void restore(List<ChangeEvent> batch) {
        // Called under buildLock. New arrivals always win over drained entries.
        for (ChangeEvent event : batch) pendingClassFiles.putIfAbsent(event.getPath(), event);
    }

    private void applyClassBatch(List<ChangeEvent> batch) {
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
        synchronized (buildLock) {
            if (pendingClassFiles.isEmpty()) return new ArrayList<>();
            List<ChangeEvent> drained = new ArrayList<>(pendingClassFiles.values());
            pendingClassFiles.clear();
            return drained;
        }
    }
}
