/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;

import java.util.function.LongSupplier;

/**
 * Says so when a reload has been running for too long.
 *
 * <p>Reloads run one after another on a single thread, on purpose: the
 * framework state they clear and refill is not written to be entered twice at
 * once. The cost of that is that one reload which never finishes, a bean whose
 * destroy method waits on a lock, a code generator that hangs, holds every save
 * made after it, and nothing says so. The developer sees the same thing they
 * see when the watcher has died: saving does nothing. That silence is the
 * failure this project keeps meeting, so this reports it, once per reload, with
 * where the reload thread is, and again when the reload does finish.
 *
 * <p>Nothing is interrupted or killed. A reload halfway through a framework
 * refresh is not something to abandon from outside; the sentence is what is
 * missing, not a timeout.
 */
public final class ReloadStall {

    private final LongSupplier clock;
    private final long warnAfterMs;

    private volatile String current;
    private volatile long startedAt;
    private volatile boolean warned;
    private volatile Thread worker;

    /**
     * @param clock       milliseconds, injectable so the wait can be tested
     *                    without waiting
     * @param warnAfterMs how long a reload may run before it is reported
     */
    public ReloadStall(LongSupplier clock, long warnAfterMs) {
        this.clock = clock;
        this.warnAfterMs = warnAfterMs;
    }

    /** {@code work}, bracketed so this knows when it runs and for how long. */
    public Runnable timed(String what, Runnable work) {
        return () -> {
            begin(what);
            try {
                work.run();
            } finally {
                end();
            }
        };
    }

    void begin(String what) {
        worker = Thread.currentThread();
        startedAt = clock.getAsLong();
        warned = false;
        current = what;
    }

    void end() {
        String what = current;
        current = null;
        if (what != null && warned) {
            StatusReporter.info(what + " finished after " + seconds(clock.getAsLong() - startedAt)
                    + ". The saves that were waiting behind it are running now.");
        }
    }

    /** Called periodically from a thread of its own; cheap when nothing is running. */
    public void check() {
        String what = current;
        if (what == null || warned) return;
        long elapsed = clock.getAsLong() - startedAt;
        if (elapsed < warnAfterMs) return;
        warned = true;
        StatusReporter.warn(what + " has been running for " + seconds(elapsed)
                + ". Saves made since then are waiting behind it" + where(worker) + ".");
    }

    /** One line for the HEALTH report, or null when no reload is running long. */
    public String healthLine() {
        String what = current;
        if (what == null) return null;
        long elapsed = clock.getAsLong() - startedAt;
        if (elapsed < warnAfterMs) return null;
        return what + " is still running, " + seconds(elapsed) + " so far; saves made since then are waiting behind it.";
    }

    private static String seconds(long ms) {
        return (ms / 1000) + "s";
    }

    /** The top of the reload thread's stack, so the report says where rather than only that. */
    private static String where(Thread thread) {
        if (thread == null) return "";
        StackTraceElement[] frames = thread.getStackTrace();
        if (frames.length == 0) return "";
        StringBuilder sb = new StringBuilder("; the reload thread is in ");
        int shown = 0;
        for (StackTraceElement frame : frames) {
            if (shown == 3) break;
            // Not the machinery that took the trace: when the checking thread
            // is the reload thread, as it is in a test, those frames are on top.
            if (frame.getClassName().equals(Thread.class.getName())
                    || frame.getClassName().equals(ReloadStall.class.getName())) continue;
            if (shown > 0) sb.append(", called from ");
            sb.append(frame.getClassName()).append('.').append(frame.getMethodName());
            if (frame.getLineNumber() > 0) sb.append(':').append(frame.getLineNumber());
            shown++;
        }
        return sb.toString();
    }
}
