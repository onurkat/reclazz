/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The watcher starts when the application is ready, not when a number says it
 * might be.
 *
 * <p>The fixed thirty-second delay was protecting something real: registering a
 * watch per directory while a server opens thousands of files of its own
 * competes for file descriptors. What it could not do is tell the difference
 * between a Hybris install and a Spring Boot application that is serving
 * requests four seconds in, so everyone paid the Hybris price.
 *
 * <p>Spring's context refresh is already instrumented, so the moment a context
 * finishes is known exactly. The clock stays as the cap, because a plain Java
 * application has no context to refresh and an application that fails to start
 * must not leave the watcher waiting for ever.
 */
class StartupSignalTest {

    /**
     * The signal is a one-shot latch shared by the JVM, so this test asserts
     * the shape either side of it firing. Once fired it stays fired, which is
     * the intended behaviour: an application does not become un-started.
     */
    @Test
    void theSignalIsOneWayAndTheWaitEndsWhenItArrives() throws InterruptedException {
        boolean alreadyReady = StartupSignal.isReady();

        if (!alreadyReady) {
            Thread signaller = new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                StartupSignal.applicationReady();
            });
            signaller.start();

            long waited = StartupSignal.awaitReady(30);
            signaller.join();

            assertTrue(StartupSignal.isReady(), "the signal has to be visible after it fires");
            assertTrue(waited < 30_000,
                    "waiting out the cap after the application said it was ready is the "
                    + "whole thing this replaces; waited " + waited + "ms");
        }

        assertTrue(StartupSignal.isReady());
        assertTrue(StartupSignal.awaitReady(30) < 30_000,
                "a signal that already fired must not make the next caller wait");
    }

    /**
     * A configured delay of zero means start now, and it has to keep meaning
     * that: it is the escape hatch for anyone whose application the signal
     * never reaches.
     */
    @Test
    void aZeroCapReturnsImmediately() throws InterruptedException {
        long start = System.nanoTime();

        assertEquals(0, StartupSignal.awaitReady(0));

        assertTrue((System.nanoTime() - start) / 1_000_000L < 500,
                "zero has to mean zero, not a settle period");
        assertEquals(0, StartupSignal.awaitReady(-5), "a negative cap is the same answer");
    }

    /**
     * The settle period exists because a refreshed context is not a finished
     * server: Hybris refreshes a global context and then builds a web context
     * per extension. It must never push the wait past the cap the caller asked
     * for, or the cap stops being a cap.
     */
    @Test
    void theSettleNeverOutlastsTheCap() throws InterruptedException {
        StartupSignal.applicationReady();

        long start = System.nanoTime();
        StartupSignal.awaitReady(1);
        long elapsed = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsed <= 1_500,
                "a one second cap cannot become a two second settle; took " + elapsed + "ms");
    }
}
