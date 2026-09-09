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
 * What a call costs once its body lives in the companion.
 *
 * <p>Measured separately, outside any reload: attaching the agent costs about
 * 1.4 nanoseconds per call, the same whether the caller is watched, where the
 * call site is rewritten to invokedynamic, or not, where it goes through the
 * trampoline. On a method that does nothing that is 0.3ns becoming 1.7ns; on
 * one that concatenates two strings it is 19.4ns against 19.2ns, which is to
 * say nothing at all.
 *
 * <p>The state this covers is the one a developer spends the day in rather than
 * the one they start it in: after a structural reload, when the dispatch goes
 * to a companion class. The bound here is deliberately loose. It is not a
 * benchmark and would be a flaky one; it is there to catch the difference
 * between a dispatch and a lookup, which is the failure that would not announce
 * itself and would be two orders of magnitude, not a few nanoseconds.
 */
class DispatchStaysCheapAfterReloadTest {

    /** Far above the measured cost, far below anything doing work per call. */
    private static final double CEILING_NS = 200.0;

    @TempDir
    Path tmp;

    @Test
    void aCompanionDispatchIsStillNanosecondsNotMicroseconds() throws Exception {
        try (WatchedApp app = WatchedApp.in(tmp)
                .with("Work", work("v1", false))
                .with("App", driver())
                .start()) {

            app.awaitOrFail("BENCH tag=v1", "the app never reported its first measurement");
            double before = nsPerCall(app.latest("BENCH tag=v1"));

            app.rewrite("Work", work("v2", true));
            app.awaitOrFail("BENCH tag=v2", "the reload never reached the app");

            // The first measurement after a redefinition is the JIT throwing
            // away what it had compiled and starting again, which is real but
            // is not what a call costs. Measured once it has settled: taking
            // the first one read 9.89 ns against 1.89 ns three seconds later,
            // and it would have been documented as a penalty that is not there.
            Thread.sleep(4000);
            double after = nsPerCall(app.latest("BENCH tag=v2"));

            System.out.printf("[diag] reload oncesi %.2f ns/cagri, sonrasi %.2f ns/cagri%n",
                    before, after);

            assertTrue(after < CEILING_NS,
                    () -> String.format("a call through the companion cost %.2f ns, which is not "
                            + "a dispatch: %.2f ns before the reload", after, before));
        }
    }

    private static double nsPerCall(String line) {
        assertNotNull(line, "no measurement was reported");
        return Double.parseDouble(line.replaceAll(".*ns=([0-9.]+).*", "$1"));
    }

    @Test
    void anExternallyCalledAddedPlainMethodAvoidsPerCallBoxing() throws Exception {
        var management = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                management instanceof com.sun.management.ThreadMXBean allocation
                        && allocation.isThreadAllocatedMemorySupported(),
                "this JDK does not expose thread allocation measurements");
        try (WatchedApp app = WatchedApp.in(tmp)
                .jvmArgs("-Xmx256m")
                .with("Work", work("v1", false))
                .with("Caller", addedMethodMeasurement(false))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Work work = new Work();
                                while (true) {
                                    Thread.sleep(500);
                                    System.out.println(Caller.measure(work));
                                }
                            }
                        }
                        """)
                .start()) {
            app.awaitOrFail("ADDED tag=v1", "no initial measurement");
            // Measure a save in a running session, not the separate startup
            // catch-up path (see CallerAndCalleeSavedTogetherTest).
            app.awaitOrFail("] Watching 1 director", "the file watcher never became ready");
            app.rewriteAll(java.util.Map.of(
                    "Work", work("v2", false).replace("private int counter;", """
                            private int counter;
                            public int addedStep(int a, int b) { counter += a + b; return counter; }
                            """),
                    "Caller", addedMethodMeasurement(true)));
            app.awaitOrFail("ADDED tag=v2", "external caller never reached the added method");
            Thread.sleep(4000); // Same post-reload JIT settling period as the existing dispatch check.
            String sample = app.latest("ADDED tag=v2");
            double ns = nsPerCall(sample);
            double bytes = Double.parseDouble(sample.replaceAll(".*bytes=([0-9.]+).*", "$1"));
            System.out.println("[added-dispatch] " + sample);
            assertTrue(ns < CEILING_NS, () -> "added-method dispatch exceeds existing ceiling: " + sample);
            // Timing alone misses the audited regression (~70 ns is below 200).
            // A primitive loop should not allocate even one boxed argument per call.
            assertTrue(bytes < 16.0, () -> "plain added calls allocate per invocation: " + sample);
        }
    }

    private static String addedMethodMeasurement(boolean added) {
        String call = added ? "work.addedStep(513, 769)" : "work.step(1282)";
        return """
                package app;
                public class Caller {
                    private static volatile long sink;
                    public static String measure(Work work) {
                        var allocation = (com.sun.management.ThreadMXBean)
                                java.lang.management.ManagementFactory.getThreadMXBean();
                        if (!allocation.isThreadAllocatedMemoryEnabled())
                            allocation.setThreadAllocatedMemoryEnabled(true);
                        long thread = Thread.currentThread().getId();
                        long sum = 0;
                        for (int i = 0; i < 2_000_000; i++) sum += %s;
                        long allocated = allocation.getThreadAllocatedBytes(thread);
                        long start = System.nanoTime();
                        int n = 2_000_000;
                        for (int i = 0; i < n; i++) sum += %s;
                        double ns = (double) (System.nanoTime() - start) / n;
                        double bytes = (double) (allocation.getThreadAllocatedBytes(thread) - allocated) / n;
                        sink = sum;
                        return String.format(java.util.Locale.ROOT,
                                "ADDED tag=%s ns=%%.2f bytes=%%.2f sum=%%d", ns, bytes, sink);
                    }
                }
                """.formatted(call, call, added ? "v2" : "v1");
    }

    private static String work(String tag, boolean withAddedMember) {
        return """
                package app;
                public class Work {
                    %s
                    private int counter;
                    public int step(int by) { counter += by; return counter; }
                    public String tag() { return "%s"; }
                }
                """.formatted(withAddedMember ? "private String added = \"added\";" : "", tag);
    }

    private static String driver() {
        return """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Work work = new Work();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(500);
                            for (int i = 0; i < 2_000_000; i++) work.step(1);
                            long n = 5_000_000L;
                            long start = System.nanoTime();
                            for (long i = 0; i < n; i++) work.step(1);
                            double ns = (double) (System.nanoTime() - start) / n;
                            System.out.printf("BENCH tag=%s ns=%.2f%n", work.tag(), ns);
                        }
                    }
                }
                """;
    }
}
