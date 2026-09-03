/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mutual exclusion across a structural reload.
 *
 * <p>A synchronized method's body moves into the companion when the class is
 * reloaded structurally, and the companion holds it as a static method. A
 * static method's own synchronized flag would take the companion class's
 * monitor rather than the object's, so the copied body took no monitor at all
 * and the methods stopped excluding each other, which is a silent loss of the
 * thing the keyword is for.
 *
 * <p>Measured rather than asserted about: two threads each calling a
 * synchronized method that holds for 300ms. Excluding, that is about 600ms
 * wall clock; not excluding, about 300ms. The number is checked before the
 * reload, where it has always worked, and after it, which is what this is for.
 */
class SynchronizedSurvivesReloadTest {

    /** Long enough that the difference is not scheduling noise. */
    private static final int HOLD_MS = 300;

    @TempDir
    Path tmp;

    @Test
    void twoThreadsStillExcludeEachOtherAfterAStructuralReload() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Guarded", guarded("v1", false))
                .with("App", driver())
                .start()) {

            app.awaitOrFail("MEASURED tag=v1", "the app did not report its first measurement");
            long before = elapsed(app.latest("MEASURED tag=v1"));
            System.out.println("[diag] reload oncesi: " + before + "ms");
            assertTrue(before >= 2L * HOLD_MS - 60,
                    "two synchronized calls should not overlap even before any reload, saw "
                            + before + "ms");

            // A structural change: the body moves into a companion.
            app.rewrite("Guarded", guarded("v2", true));
            app.awaitOrFail("MEASURED tag=v2", "the reload never reached the app");
            long after = elapsed(app.latest("MEASURED tag=v2"));
            System.out.println("[diag] reload sonrasi: " + after + "ms");

            assertTrue(after >= 2L * HOLD_MS - 60,
                    "the structural reload moved the body to a companion and the two calls "
                            + "started overlapping: " + after + "ms against " + before
                            + "ms before, where excluding is about " + (2 * HOLD_MS) + "ms");
        }
    }

    private static long elapsed(String line) {
        assertNotNull(line, "no measurement was reported");
        return Long.parseLong(line.replaceAll(".*elapsed=(\\d+).*", "$1"));
    }

    private static String guarded(String tag, boolean withAddedMember) {
        return """
                package app;
                public class Guarded {
                    %s
                    public synchronized String hold() throws InterruptedException {
                        Thread.sleep(%d);
                        return "%s";
                    }
                }
                """.formatted(withAddedMember ? "private String added = \"added\";" : "",
                        HOLD_MS, tag);
    }

    /** Times two threads through the method and prints how long it took. */
    private static String driver() {
        return """
                package app;
                import java.util.concurrent.*;

                public class App {
                    public static void main(String[] args) throws Exception {
                        Guarded guarded = new Guarded();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(600);
                            String tag = guarded.hold();
                            CountDownLatch ready = new CountDownLatch(2);
                            CountDownLatch go = new CountDownLatch(1);
                            CountDownLatch done = new CountDownLatch(2);
                            for (int i = 0; i < 2; i++) {
                                Thread t = new Thread(() -> {
                                    try {
                                        ready.countDown();
                                        go.await();
                                        guarded.hold();
                                    } catch (Throwable ignored) {
                                    } finally {
                                        done.countDown();
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            }
                            ready.await();
                            long start = System.currentTimeMillis();
                            go.countDown();
                            done.await();
                            System.out.println("MEASURED tag=" + tag
                                    + " elapsed=" + (System.currentTimeMillis() - start));
                        }
                    }
                }
                """;
    }

    /**
     * The dangerous half. If the wrapper releases on the ordinary way out and
     * not on the exceptional one, the monitor is held forever and the next
     * call into the object never returns: a deadlock that looks like a hang
     * rather than an error, and only after a reload.
     */
    @Test
    void aBodyThatThrowsStillReleasesTheMonitor() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Thrower", thrower("v1", false))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Thrower thrower = new Thrower();
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(400);
                                    String state;
                                    try {
                                        thrower.boom();
                                        state = "NO_THROW";
                                    } catch (IllegalStateException expected) {
                                        // The monitor has to be free now. On
                                        // another thread, so a leaked one
                                        // shows up as a timeout rather than
                                        // being hidden by reentrancy.
                                        state = reentered(thrower) ? "FREE" : "HELD";
                                    }
                                    System.out.println("STATE tag=" + thrower.tag()
                                            + " monitor=" + state);
                                }
                            }

                            static boolean reentered(Thrower thrower) throws Exception {
                                Thread t = new Thread(() -> {
                                    try {
                                        thrower.quiet();
                                    } catch (Throwable ignored) {
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                                t.join(3000);
                                return !t.isAlive();
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("tag=v1 monitor=FREE",
                    "the monitor was not released after a throw, before any reload");

            app.rewrite("Thrower", thrower("v2", true));
            app.awaitOrFail("tag=v2 monitor=",
                    "the reload never reached the app");

            String state = app.latest("STATE tag=v2");
            System.out.println("[diag] " + state);
            assertTrue(state.contains("monitor=FREE"),
                    () -> "a body that threw kept the monitor after its move to the companion, "
                            + "so the next call into the object hangs: " + state);
        }
    }

    /** A static synchronized method locks the class, not any object. */
    @Test
    void aStaticSynchronizedMethodStillLocksItsClass() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Shared", shared("v1", false))
                .with("App", """
                        package app;
                        import java.util.concurrent.*;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                System.out.println("APP_STARTED");
                                while (true) {
                                    Thread.sleep(600);
                                    String tag = Shared.hold();
                                    CountDownLatch go = new CountDownLatch(1);
                                    CountDownLatch done = new CountDownLatch(2);
                                    for (int i = 0; i < 2; i++) {
                                        Thread t = new Thread(() -> {
                                            try { go.await(); Shared.hold(); }
                                            catch (Throwable ignored) { }
                                            finally { done.countDown(); }
                                        });
                                        t.setDaemon(true);
                                        t.start();
                                    }
                                    Thread.sleep(100);
                                    long start = System.currentTimeMillis();
                                    go.countDown();
                                    done.await();
                                    System.out.println("MEASURED tag=" + tag
                                            + " elapsed=" + (System.currentTimeMillis() - start));
                                }
                            }
                        }
                        """)
                .start()) {

            app.awaitOrFail("MEASURED tag=v1", "the app did not report its first measurement");
            long before = elapsed(app.latest("MEASURED tag=v1"));

            app.rewrite("Shared", shared("v2", true));
            app.awaitOrFail("MEASURED tag=v2", "the reload never reached the app");
            long after = elapsed(app.latest("MEASURED tag=v2"));
            System.out.println("[diag] static: " + before + "ms -> " + after + "ms");

            assertTrue(after >= 2L * HOLD_MS - 60,
                    "a static synchronized method stopped locking its class after the reload: "
                            + after + "ms against " + before + "ms before");
        }
    }

    private static String thrower(String tag, boolean withAddedMember) {
        return """
                package app;
                public class Thrower {
                    %s
                    public synchronized void boom() {
                        throw new IllegalStateException("expected");
                    }
                    public synchronized void quiet() { }
                    public String tag() { return "%s"; }
                }
                """.formatted(withAddedMember ? "private String added = \"added\";" : "", tag);
    }

    private static String shared(String tag, boolean withAddedMember) {
        return """
                package app;
                public class Shared {
                    %s
                    public static synchronized String hold() throws InterruptedException {
                        Thread.sleep(%d);
                        return "%s";
                    }
                }
                """.formatted(withAddedMember ? "private static String added = \"added\";" : "",
                        HOLD_MS, tag);
    }
}
