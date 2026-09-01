/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
 */
class ReloadUnderLoadTest {

    private static final long BOOT_TIMEOUT_SEC = 60;
    private static final long RELOAD_TIMEOUT_SEC = 60;

    private static final int THREADS = 8;

    @TempDir
    Path tmp;

    @Test
    void reloadingWhileEightThreadsCallInBreaksNothing() throws Exception {
        String agentJar = System.getProperty("reclazz.agent.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                agentJar != null && Files.exists(Path.of(agentJar)),
                "agent shadow jar not built — run :agent:shadowJar");

        Path srcDir = Files.createDirectories(tmp.resolve("src/app"));
        Path classesDir = Files.createDirectories(tmp.resolve("classes"));

        Path work = srcDir.resolve("Work.java");
        Files.writeString(work, bodyChange("v1"));
        Files.writeString(srcDir.resolve("App.java"), driverSource());
        compile(List.of(srcDir.resolve("App.java"), work), classesDir, "");

        Process app = new ProcessBuilder(
                javaBinary(),
                "-javaagent:" + agentJar + "=watchDirs=" + classesDir
                        + ",startupDelaySec=1,debounceMs=200",
                "-cp", classesDir.toString(),
                "app.App")
                .redirectErrorStream(true)
                .start();

        List<String> output = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(app.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) output.add(line);
            } catch (Exception ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            assertTrue(awaitLine(output, "APP_STARTED", BOOT_TIMEOUT_SEC),
                    () -> "app did not start under the agent:\n" + tail(output));
            assertTrue(awaitLast(output, "v1", BOOT_TIMEOUT_SEC),
                    () -> "threads never saw the first version:\n" + tail(output));

            // A plain body change, then a structural one that adds a field and
            // a method, then a structural one that takes them away again, then
            // a body change on the far side of all that.
            serve(output, work, classesDir, bodyChange("v2"), "v2");
            serve(output, work, classesDir, structural("v3"), "v3");
            serve(output, work, classesDir, bodyChange("v4"), "v4");
            serve(output, work, classesDir, structural("v5"), "v5");

            String state = lastState(output);
            assertNotNull(state, "the driver never reported its state");
            System.out.println("[diag] " + state);

            assertTrue(state.contains("errors=0"),
                    () -> "a call into the class failed while it was being reloaded:\n" + tail(output));
            assertTrue(state.contains("unknown=0"),
                    () -> "a call returned a value no version of the class ever returned:\n" + tail(output));

            long calls = Long.parseLong(state.replaceAll(".*calls=(\\d+).*", "$1"));
            assertTrue(calls > 1_000_000,
                    "the load was supposed to be heavy enough to overlap the reloads, saw " + calls);
        } finally {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** Write the new source, compile it into the watched directory, wait for the threads to see it. */
    private void serve(List<String> output, Path source, Path classesDir,
                       String newSource, String expected) throws Exception {
        Files.writeString(source, newSource);
        compile(List.of(source), classesDir, "");
        assertTrue(awaitLast(output, expected, RELOAD_TIMEOUT_SEC),
                () -> "threads never saw " + expected + " (the old body kept being served):\n"
                        + tail(output));
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
                import java.util.concurrent.*;
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

    private static void compile(List<Path> sources, Path outputDir, String classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required to run this test");
        List<String> args = new ArrayList<>(List.of("-d", outputDir.toString(), "-nowarn"));
        if (!classpath.isEmpty()) {
            args.add("-classpath");
            args.add(classpath);
        }
        sources.forEach(s -> args.add(s.toString()));
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "fixture compilation failed");
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** The driver's most recent report, which is the one that counts. */
    private static String lastState(List<String> output) {
        String found = null;
        for (String line : output) {
            if (line.startsWith("STATE ")) found = line;
        }
        return found;
    }

    private static boolean awaitLine(List<String> output, String needle, long timeoutSec)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (output.stream().anyMatch(l -> l.contains(needle))) return true;
            Thread.sleep(200);
        }
        return false;
    }

    /** Waits for the threads to be currently seeing this value, not to have seen it once. */
    private static boolean awaitLast(List<String> output, String value, long timeoutSec)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            String state = lastState(output);
            if (state != null && state.contains("last=" + value + " ")) return true;
            Thread.sleep(200);
        }
        return false;
    }

    private static String tail(List<String> output) {
        List<String> lines = new ArrayList<>(output);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()));
    }
}
