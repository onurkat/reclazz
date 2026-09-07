/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestGateTest {
    @Test
    void timeoutReopensAdmissionAndTheSameEditCanRetry() throws Exception {
        RequestGate gate = ready();
        gate.enter();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch deferred = new CountDownLatch(1);
        CountDownLatch applied = new CountDownLatch(1);
        try {
            Future<?> reload = workers.submit(() -> {
                try {
                    assertFalse(gate.tryBeginReload(50));
                    deferred.countDown();
                    gate.awaitIdle();
                    assertTrue(gate.tryBeginReload(1000));
                    try { applied.countDown(); }
                    finally { gate.endReload(); }
                } catch (InterruptedException e) { throw new AssertionError(e); }
            });
            assertTrue(deferred.await(2, TimeUnit.SECONDS));
            workers.submit(() -> { gate.enter(); gate.exit(); }).get(2, TimeUnit.SECONDS);
            assertEquals(1, applied.getCount(), "original request still owns its code version");
            gate.exit();
            reload.get(2, TimeUnit.SECONDS);
            assertEquals(0, applied.getCount());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void nestedDispatchDoesNotDeadlockAgainstTheWaitingReloader() throws Exception {
        RequestGate gate = ready();
        gate.enter();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reload = new Thread(() -> {
            try {
                assertTrue(gate.tryBeginReload(2000));
                gate.endReload();
            } catch (Throwable e) { failure.set(e); }
        });
        reload.start();
        try {
            awaitWaiting(reload);
            gate.enter();
            gate.exit();
            gate.exit();
            reload.join(2000);
            assertFalse(reload.isAlive());
            assertNull(failure.get());
        } finally { reload.interrupt(); }
    }

    @Test
    void noNewRequestEntersUntilTheCompleteReloadHasFinished() throws Exception {
        RequestGate gate = ready();
        assertTrue(gate.tryBeginReload(10));
        CountDownLatch entered = new CountDownLatch(1);
        Thread request = new Thread(() -> { gate.enter(); entered.countDown(); gate.exit(); });
        request.start();
        try {
            awaitWaiting(request);
            assertEquals(1, entered.getCount());
        } finally { gate.endReload(); }
        request.join(2000);
        assertEquals(0, entered.getCount());
        assertFalse(request.isAlive());
    }

    @Test
    void shutdownInterruptReopensAdmissionWithoutApplyingTheEdit() throws Exception {
        RequestGate gate = ready();
        gate.enter();
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread reload = new Thread(() -> {
            try {
                gate.tryBeginReload(5000);
                fail("request was never released");
            } catch (InterruptedException expected) { interrupted.countDown(); }
        });
        reload.start();
        try {
            awaitWaiting(reload);
            reload.interrupt();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            Thread next = new Thread(() -> { gate.enter(); gate.exit(); });
            next.start(); next.join(2000);
            assertFalse(next.isAlive(), "interrupted drain left admission closed");
        } finally { gate.exit(); reload.interrupt(); }
    }

    @Test
    void missingOrFailedHooksNeverGiveAnUnprotectedReloadPermission() throws Exception {
        RequestGate gate = new RequestGate();
        assertFalse(gate.tryBeginReload(1));
        gate.installed();
        assertTrue(gate.tryBeginReload(1));
        gate.endReload();
        gate.unavailable("unsupported MVC version");
        assertFalse(gate.tryBeginReload(1));
        assertEquals("unsupported MVC version", gate.waitingFor());
    }

    @Test
    void nestedRequestOnTheReloadThreadCanReturn() throws Exception {
        RequestGate gate = ready();
        assertTrue(gate.tryBeginReload(1));
        try { gate.enter(); gate.enter(); gate.exit(); gate.exit(); }
        finally { gate.endReload(); }
        assertTrue(gate.tryBeginReload(1));
        gate.endReload();
    }

    private static RequestGate ready() {
        RequestGate gate = new RequestGate();
        gate.installed();
        return gate;
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
