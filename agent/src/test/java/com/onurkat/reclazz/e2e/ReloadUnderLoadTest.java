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
 * Reloads a class while eight threads are calling into it.
 *
 * <p>Every other test in this suite reloads a class that nobody is using at
 * that moment, which is the easy half of the problem. A running application is
 * the other half: threads are inside the method, or about to enter it, when the
 * dispatch is swapped. What could go wrong there does not go wrong quietly. A
 * torn swap throws from the developer's own call, a missed invalidation leaves
 * the old body running forever, and either one is the kind of thing that
 * reproduces once a week on someone else's machine.
 *
 * <p>The loop is deliberately tight so the JIT compiles and inlines the call
 * before the first reload. That makes the test harder rather than easier: a
 * redefinition that does not invalidate the compiled call site would keep
 * serving the old answer, and this notices.
 *
 * <p>Both reload paths are exercised under the same load. A body change goes
 * through plain redefinition; adding a field and a method forces the structural
 * path, which builds a companion class and moves the instance's added-field
 * storage, on an object eight threads are reading through.
 *
 * <p>What it asserts is what an application is entitled to: no call fails, no
 * call returns something nobody wrote, and every version reaches the threads.
 *
 * <p>Skipped when the agent jar has not been built, like the smoke test.
 */class ReloadUnderLoadTest {

    private static final int THREADS = 8;

    @TempDir
    Path tmp;

    @Test
    void reloadingWhileEightThreadsCallInBreaksNothing() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Work", bodyChange("v1"))
                .with("App", driverSource())
                .start()) {

            app.awaitOrFail("APP_STARTED", "app did not start under the agent");
            app.awaitOrFail("last=v1 ", "threads never saw the first version");

            // A plain body change, then a structural one that adds a field and
            // a method, then a structural one that takes them away again, then
            // a body change on the far side of all that.
            serve(app, bodyChange("v2"), "v2");
            serve(app, structural("v3"), "v3");
            serve(app, bodyChange("v4"), "v4");
            serve(app, structural("v5"), "v5");

            String state = app.latest("STATE ");
            assertNotNull(state, "the driver never reported its state");
            System.out.println("[diag] " + state);

            assertTrue(state.contains("errors=0"),
                    () -> "a call into the class failed while it was being reloaded:\n"
                            + app.tail());
            assertTrue(state.contains("unknown=0"),
                    () -> "a call returned a value no version of the class ever returned:\n"
                            + app.tail());

            long calls = Long.parseLong(state.replaceAll(".*calls=(\\d+).*", "$1"));
            assertTrue(calls > 1_000_000,
                    "the load was supposed to be heavy enough to overlap the reloads, saw " + calls);
        }
    }

    /** Write the new source, and wait for the threads to be seeing it. */
    private static void serve(WatchedApp app, String newSource, String expected) throws Exception {
        app.rewrite("Work", newSource);
        app.awaitOrFail("last=" + expected + " ",
                "threads never saw " + expected + " (the old body kept being served)");
    }

    private static String bodyChange(String value) {
        return """
                package app;
                public class Work {
                    public String tag() { return "%s"; }
                }
                """.formatted(value);
    }

    /** Adds a field and a method, which is what forces the structural path. */
    private static String structural(String value) {
        return """
                package app;
                public class Work {
                    private final String added = "%s";
                    public String tag() { return helper(); }
                    private String helper() { return added == null ? "%s" : added; }
                }
                """.formatted(value, value);
    }

    /**
     * Eight threads calling in a tight loop, reporting what they see. A value
     * outside the set any version ever returned is counted separately from a
     * thrown exception: they are different failures and the difference is worth
     * keeping.
     */
    private static String driverSource() {
        return """
                package app;
                import java.util.*;
                import java.util.concurrent.atomic.*;

                public class App {
                    static final AtomicLong calls = new AtomicLong();
                    static final AtomicLong unknown = new AtomicLong();
                    static final List<String> errors =
                            Collections.synchronizedList(new ArrayList<>());
                    static final Set<String> known =
                            new HashSet<>(Arrays.asList("v1", "v2", "v3", "v4", "v5"));
                    static volatile String last = "none";

                    public static void main(String[] args) throws Exception {
                        Work work = new Work();
                        for (int i = 0; i < %d; i++) {
                            Thread t = new Thread(() -> {
                                while (true) {
                                    try {
                                        String v = work.tag();
                                        if (v == null || !known.contains(v)) {
                                            unknown.incrementAndGet();
                                        } else {
                                            last = v;
                                        }
                                        calls.incrementAndGet();
                                    } catch (Throwable failure) {
                                        if (errors.size() < 20) {
                                            errors.add(failure.getClass().getName()
                                                    + ": " + failure.getMessage());
                                        }
                                    }
                                }
                            });
                            t.setDaemon(true);
                            t.start();
                        }
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(250);
                            System.out.println("STATE last=" + last
                                    + " calls=" + calls.get()
                                    + " unknown=" + unknown.get()
                                    + " errors=" + errors.size()
                                    + (errors.isEmpty() ? "" : " first=" + errors.get(0)));
                        }
                    }
                }
                """.formatted(THREADS);
    }
}
