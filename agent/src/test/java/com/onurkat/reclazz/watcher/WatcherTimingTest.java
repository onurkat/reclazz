/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.watcher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatcherTimingTest {
    private final AtomicLong now = new AtomicLong();
    private final WatcherTiming timing = new WatcherTiming(now::get);

    private WatcherHealth health(long second, boolean tick) {
        now.set(TimeUnit.SECONDS.toNanos(second));
        return timing.health(Thread.currentThread(), "watching", 0, 200, null, tick);
    }

    @Test void uninterruptedCheckerRequiresThirtySecondsOfPollSilence() {
        for (int s = 0; s < 30; s++) assertEquals("watching", health(s, true).state());
        assertEquals("stalled", health(30, true).state());
        assertEquals("watching", timing.health(Thread.currentThread(), "watching",
                now.get(), 200, null, true).state());
    }

    @Test void diagnosticReadsDuringSuspensionCannotConsumeTheGracePeriod() {
        health(0, true);
        for (int s = 1; s <= 45; s++) assertEquals("watching", health(s, false).state());
        for (int s = 45; s < 75; s++) assertEquals("watching", health(s, true).state());
        assertEquals("stalled", health(75, true).state());
        assertEquals("stopped", timing.health(new Thread(), "watching", 0, 200, null, false).state());
    }

    private static class Deadline extends CompletableFuture<Integer> {
        int calls;
        final IntConsumer atDeadline;

        Deadline(IntConsumer atDeadline) { this.atDeadline = atDeadline; }

        @Override public Integer get(long duration, TimeUnit unit) throws TimeoutException {
            assertEquals(10, unit.toSeconds(duration));
            atDeadline.accept(++calls);
            if (isDone()) return join();
            throw new TimeoutException();
        }
    }

    @Test void waiterCanWakeBeforeCheckerAndKeepItsOriginalBarrier() throws Exception {
        health(0, true);
        var barrier = new Deadline(call -> health(11, false)) {
            @Override public Integer get(long duration, TimeUnit unit) throws TimeoutException {
                if (calls == 1) complete(7);
                return super.get(duration, unit);
            }
        };
        assertEquals(7, timing.awaitScan(barrier));
        assertEquals(2, barrier.calls);
    }

    @Test void twoCheckerGapsGiveOnlyOneExtension() {
        health(0, true);
        var barrier = new Deadline(call -> health(call * 11, true));
        assertThrows(TimeoutException.class, () -> timing.awaitScan(barrier));
        assertEquals(2, barrier.calls);
    }

    @Test void healthyCheckerDoesNotExtendAnUndeliveredScan() {
        health(0, true);
        var barrier = new Deadline(call -> {
            for (int s = 1; s <= 10; s++) health(s, true);
        });
        assertThrows(TimeoutException.class, () -> timing.awaitScan(barrier));
        assertEquals(1, barrier.calls);
    }
}
