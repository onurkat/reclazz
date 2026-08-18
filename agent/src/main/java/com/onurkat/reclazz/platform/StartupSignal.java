/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * When the application is far enough along to be watched.
 *
 * <p>The watcher used to sleep for a fixed thirty seconds before registering
 * its directories. The reason is real: registering a watch per directory while
 * a server is opening thousands of files of its own competes for file
 * descriptors, and a Hybris install has tens of thousands of class files to
 * hash on top of that. The number was a guess at how long that lasts.
 *
 * <p>A guess is fine when it is too long and wrong when it is too short, so it
 * was set long, and every application that starts in four seconds pays
 * twenty-six seconds of nothing. On a Spring Boot application that is most of
 * the wait between saving a file and seeing it reload for the first time.
 *
 * <p>There is a better signal already being collected. Spring's
 * {@code AbstractApplicationContext.refresh()} is instrumented to register the
 * context on its way out, so the moment a context finishes refreshing is known
 * exactly. Waiting for that instead of for the clock starts the watcher when
 * the application says it is ready rather than when a number says it might be.
 *
 * <p>The clock stays as the upper bound, because nothing guarantees a signal:
 * a plain Java application has no context to refresh, and an application that
 * fails to start should not leave the watcher waiting for ever. So this is
 * "whichever comes first", and the configured delay keeps its meaning as the
 * longest the watcher will wait.
 */
public final class StartupSignal {

    private static final CountDownLatch READY = new CountDownLatch(1);

    /**
     * How long to let the application settle after it reports ready.
     *
     * <p>A context finishing its refresh is not the same as a server having
     * finished starting: Hybris refreshes a global context and then goes on
     * building web contexts for every extension. Registering watches into the
     * middle of that is the case the original delay was protecting against, so
     * the signal buys most of the wait back rather than all of it.
     */
    private static final long SETTLE_MILLIS = 2_000L;

    private StartupSignal() {
    }

    /** Called when an application context has finished refreshing. */
    public static void applicationReady() {
        READY.countDown();
    }

    /** Whether the signal has already arrived. */
    public static boolean isReady() {
        return READY.getCount() == 0;
    }

    /**
     * Block until the application reports ready, or the cap expires.
     *
     * @param capSeconds the longest to wait, which is the configured startup
     *                   delay; zero or less returns at once
     * @return the number of milliseconds actually waited, for the log
     */
    public static long awaitReady(int capSeconds) throws InterruptedException {
        if (capSeconds <= 0) return 0;

        long start = System.nanoTime();
        boolean signalled = READY.await(capSeconds, TimeUnit.SECONDS);
        if (signalled) {
            // Settle, but never past the cap the caller asked for.
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            long remaining = Math.max(0, capSeconds * 1000L - elapsed);
            Thread.sleep(Math.min(SETTLE_MILLIS, remaining));
        }
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
