/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AsyncRequestGateTest {
    @Test void leaseOutlivesDispatchAndCompletionIsIdempotent() throws Exception {
        RequestGate gate = ready();
        var lease = gate.lease();
        assertThrows(IllegalStateException.class, lease::start);
        gate.enter(); lease.start(); lease.start(); gate.exit();
        assertFalse(gate.tryBeginReload(1));
        lease.close(); lease.close();
        assertReloadable(gate);
        gate.enter();
        try { assertThrows(IllegalStateException.class, lease::start); }
        finally { gate.exit(); }
        assertReloadable(gate);
    }

    @Test void admittedRedispatchCanFinishWhileWriterDrains() throws Exception {
        RequestGate gate = ready();
        var lease = gate.lease();
        gate.enter(); lease.start(); gate.exit();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread reload = new Thread(() -> {
            try { assertTrue(gate.tryBeginReload(2000)); gate.endReload(); }
            catch (Throwable e) { failure.set(e); }
        });
        reload.start();
        try {
            awaitWaiting(reload);
            gate.enter(lease); // Must bypass the draining writer, without ending the lease.
            lease.close();
            assertTrue(reload.isAlive());
            gate.exit();
            reload.join(2000);
            assertFalse(reload.isAlive()); assertNull(failure.get());
        } finally { lease.close(); reload.interrupt(); }
    }

    @Test void completedRequestDoesNotReleaseAStillRunningWorker() throws Exception {
        RequestGate gate = ready();
        var lease = gate.lease();
        gate.enter(); lease.start(); gate.exit();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var worker = executor.submit(() -> {
                gate.enter(lease); entered.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                finally { gate.exit(); }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                lease.close();
                assertFalse(gate.tryBeginReload(1));
            } finally { release.countDown(); }
            worker.get(2, TimeUnit.SECONDS);
        }
        assertReloadable(gate);
    }

    @Test void completedContinuationCannotBypassAnActiveReload() throws Exception {
        RequestGate gate = ready();
        var lease = gate.lease(); lease.close();
        assertTrue(gate.tryBeginReload(1));
        var entered = new CountDownLatch(1);
        Thread request = new Thread(() -> { gate.enter(lease); entered.countDown(); gate.exit(); });
        request.start();
        try { awaitWaiting(request); assertEquals(1, entered.getCount()); }
        finally { gate.endReload(); }
        request.join(2000);
        assertFalse(request.isAlive()); assertEquals(0, entered.getCount());
    }

    @Test void foreignLeaseCannotAuthorizeAdmission() {
        RequestGate gate = ready();
        assertThrows(IllegalArgumentException.class, () -> gate.enter(ready().lease()));
    }

    private static RequestGate ready() { var gate = new RequestGate(); gate.installed(); return gate; }
    static void assertReloadable(RequestGate gate) throws Exception {
        assertTrue(gate.tryBeginReload(10), gate.waitingFor()); gate.endReload();
    }
    private static void awaitWaiting(Thread thread) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until) {
            if (thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING) return;
            Thread.sleep(1);
        }
        fail("thread did not reach the boundary wait: " + thread.getState());
    }
}
