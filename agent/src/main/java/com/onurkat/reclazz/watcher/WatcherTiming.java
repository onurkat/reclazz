/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Independent scheduler progress distinguishes an unresponsive poller from a delayed JVM. */
final class WatcherTiming {
    private static final long MAX_TICK_GAP = TimeUnit.SECONDS.toNanos(2);
    private static final long UNSEEN = Long.MIN_VALUE;
    private final LongSupplier clock;
    private long previousTick = UNSEEN;
    private long uninterruptedSince;
    private long lastPauseEnd = UNSEEN;

    WatcherTiming(LongSupplier clock) { this.clock = clock; }

    synchronized WatcherHealth health(Thread thread, String state, long completedPoll, long pollMillis,
                                      String cause, boolean checkerTick) {
        long now = clock.getAsLong();
        boolean delayed = previousTick != UNSEEN && now - previousTick > MAX_TICK_GAP;
        if (checkerTick) {
            if (delayed) lastPauseEnd = now;
            if (delayed || previousTick == UNSEEN) uninterruptedSince = now;
            previousTick = now;
        }
        // A diagnostic read before the checker wakes must neither consume the gap nor report a stall.
        long baseline = delayed || previousTick == UNSEEN ? now : uninterruptedSince;
        return WatcherHealth.observe(thread, state, Math.max(completedPoll, baseline), now, pollMillis, cause);
    }

    int awaitScan(CompletableFuture<Integer> scan)
            throws InterruptedException, ExecutionException, TimeoutException {
        long start = clock.getAsLong();
        try {
            return scan.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException expired) {
            if (!overlappedPause(start)) throw expired;
            // Only this barrier can report delivery of the requested files. Give it one more window.
            return scan.get(10, TimeUnit.SECONDS);
        }
    }

    private synchronized boolean overlappedPause(long start) {
        long now = clock.getAsLong();
        if (lastPauseEnd != UNSEEN && lastPauseEnd > start) return true;
        // The deadline waiter can be scheduled before the first post-resume checker tick.
        return previousTick != UNSEEN && start - previousTick <= MAX_TICK_GAP && now - previousTick > MAX_TICK_GAP;
    }
}
