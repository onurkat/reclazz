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
 * Reloading both halves of a hierarchy while threads are calling through it.
 *
 * <p>A super call in an instrumented class is rewritten to the parent's renamed
 * body, so a call from the child reaches into the parent's copy directly rather
 * than through its trampoline. That is two classes agreeing about each other's
 * shape, and a reload changes one of them at a time. The interleaving that has
 * never been tested is a thread inside the child's body while the parent is
 * being redefined underneath it, and the reverse.
 *
 * <p>The load is eight threads calling the child, which calls super on every
 * invocation. Underneath them the parent is reloaded, then the child, then both
 * in one save, which is a batch redefinition of a hierarchy. What is asserted is
 * that no call fails, no call returns a value no version ever produced, and
 * every version reaches the threads.
 */
class HierarchyReloadUnderLoadTest {

    private static final int THREADS = 8;

    @TempDir
    Path tmp;

    @Test
    void reloadingParentAndChildUnderLoadBreaksNothing() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Base", base("b1"))
                .with("Derived", derived("d1"))
                .with("App", driver())
                .start()) {

            app.awaitOrFail("APP_STARTED", "app did not start under the agent");
            app.awaitOrFail("last=d1/b1 ", "threads never saw the first version");

            // The parent alone, which the child's rewritten super call reaches
            // into directly.
            app.rewrite("Base", base("b2"));
            app.awaitOrFail("last=d1/b2 ", "a reload of the parent never reached the child");

            // The child alone.
            app.rewrite("Derived", derived("d2"));
            app.awaitOrFail("last=d2/b2 ", "a reload of the child never reached the threads");

            // Both in one save, which is a batch redefinition of a hierarchy.
            app.rewrite("Base", base("b3"));
            app.rewrite("Derived", derived("d3"));
            app.awaitOrFail("last=d3/b3 ", "reloading both halves at once did not settle");

            String state = app.latest("STATE ");
            assertNotNull(state, "the driver never reported its state");
            System.out.println("[diag] " + state);

            assertTrue(state.contains("errors=0"),
                    () -> "a call through the hierarchy failed while it was being reloaded:\n"
                            + app.tail());
            assertTrue(state.contains("unknown=0"),
                    () -> "a call returned a pairing no two versions ever produced:\n"
                            + app.tail());

            long calls = Long.parseLong(state.replaceAll(".*calls=(\\d+).*", "$1"));
            assertTrue(calls > 500_000,
                    "the load was supposed to overlap the reloads, saw " + calls);
        }
    }

    private static String base(String tag) {
        return """
                package app;
                public class Base {
                    public String describe() { return "%s"; }
                }
                """.formatted(tag);
    }

    private static String derived(String tag) {
        return """
                package app;
                public class Derived extends Base {
                    @Override public String describe() { return "%s/" + super.describe(); }
                }
                """.formatted(tag);
    }

    private static String driver() {
        return """
                package app;
                import java.util.*;
                import java.util.concurrent.atomic.*;

                public class App {
                    static final AtomicLong calls = new AtomicLong();
                    static final AtomicLong unknown = new AtomicLong();
                    static final List<String> errors =
                            Collections.synchronizedList(new ArrayList<>());
                    static final Set<String> knownChild =
                            new HashSet<>(Arrays.asList("d1", "d2", "d3"));
                    static final Set<String> knownBase =
                            new HashSet<>(Arrays.asList("b1", "b2", "b3"));
                    static volatile String last = "none";

                    public static void main(String[] args) throws Exception {
                        Derived derived = new Derived();
                        for (int i = 0; i < %d; i++) {
                            Thread t = new Thread(() -> {
                                while (true) {
                                    try {
                                        String v = derived.describe();
                                        String[] halves = v.split("/", 2);
                                        boolean sane = halves.length == 2
                                                && knownChild.contains(halves[0])
                                                && knownBase.contains(halves[1]);
                                        if (sane) last = v; else unknown.incrementAndGet();
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
