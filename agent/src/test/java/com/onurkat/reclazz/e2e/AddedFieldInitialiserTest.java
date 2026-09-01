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
 * What a field with an initialiser holds on an object that already existed.
 *
 * <p>A field's initialiser is constructor code, and the object was constructed
 * before the field was written. So adding {@code private final List<String>
 * cache = new ArrayList<>();} to a class and reloading gives every live
 * instance of it a null, and the first method to touch it fails with a
 * NullPointerException on a line that reads as though it cannot produce one.
 * For a Spring singleton, and that is most of what a developer edits, every
 * instance is one that already existed.
 *
 * <p>Nothing can be done about the value: running the initialiser would mean
 * re-running the constructor on a live object, which would also reset the
 * fields it already has. What can be done is saying so, at the moment the class
 * is reloaded rather than when a request happens to hit the line, and this pins
 * that the warning is there and names the field.
 */
class AddedFieldInitialiserTest {

    private static final long TIMEOUT_SEC = 60;

    @TempDir
    Path tmp;

    @Test
    void addingAnInitialisedFieldWarnsThatLiveInstancesDoNotGetTheValue() throws Exception {
        String agentJar = System.getProperty("reclazz.agent.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                agentJar != null && Files.exists(Path.of(agentJar)),
                "agent shadow jar not built — run :agent:shadowJar");

        Path srcDir = Files.createDirectories(tmp.resolve("src/app"));
        Path classesDir = Files.createDirectories(tmp.resolve("classes"));

        Path holder = srcDir.resolve("Holder.java");
        Files.writeString(holder, """
                package app;
                public class Holder {
                    public String describe() { return "no field yet"; }
                }
                """);
        Files.writeString(srcDir.resolve("App.java"), """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Holder holder = new Holder();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(400);
                            String answer;
                            try {
                                answer = holder.describe();
                            } catch (Throwable failure) {
                                answer = failure.getClass().getSimpleName();
                            }
                            System.out.println("SAW=" + answer);
                        }
                    }
                }
                """);
        compile(List.of(srcDir.resolve("App.java"), holder), classesDir);

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
            assertTrue(await(output, "APP_STARTED", TIMEOUT_SEC),
                    () -> "app did not start under the agent:\n" + tail(output));
            assertTrue(await(output, "SAW=no field yet", TIMEOUT_SEC),
                    () -> "app did not serve the original:\n" + tail(output));

            // The edit a developer makes without thinking about it: a new
            // field, with an initialiser, read by an existing method.
            Files.writeString(holder, """
                    package app;
                    import java.util.*;
                    public class Holder {
                        private final List<String> cache = new ArrayList<>();
                        public String describe() { return "size " + cache.size(); }
                    }
                    """);
            compile(List.of(holder), classesDir);

            assertTrue(await(output, "SAW=", TIMEOUT_SEC), () -> tail(output));
            Thread.sleep(4000);

            String reached = latest(output, "SAW=");
            System.out.println("[diag] live instance answered: " + reached);
            System.out.println("[diag] agent said: " + String.join(" / ", warnings(output)));

            assertNotNull(reached, () -> "nothing was served after the reload:\n" + tail(output));

            // Whatever the value turns out to be, the developer has to have
            // been told: a null they did not write is not something to find
            // from a stack trace an hour later.
            List<String> warnings = warnings(output);
            assertFalse(warnings.isEmpty(),
                    () -> "adding an initialised field to a class with live instances said nothing:\n"
                            + tail(output));
            assertTrue(warnings.stream().anyMatch(w -> w.contains("cache")),
                    () -> "the warning did not name the field that will be empty: " + warnings);
        } finally {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** Lines the agent printed about added fields, whatever it decided to call them. */
    private static List<String> warnings(List<String> output) {
        List<String> found = new ArrayList<>();
        for (String line : output) {
            String lower = line.toLowerCase();
            boolean aboutFields = lower.contains("field");
            boolean aboutInstances = lower.contains("existing") || lower.contains("live")
                    || lower.contains("instance") || lower.contains("initialis")
                    || lower.contains("initializ") || lower.contains("default");
            if (aboutFields && aboutInstances) found.add(line);
        }
        return found;
    }

    private static String latest(List<String> output, String prefix) {
        String found = null;
        for (String line : output) {
            if (line.contains(prefix)) found = line;
        }
        return found;
    }

    private static void compile(List<Path> sources, Path outputDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required to run this test");
        List<String> args = new ArrayList<>(List.of("-d", outputDir.toString(), "-nowarn"));
        sources.forEach(s -> args.add(s.toString()));
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "fixture compilation failed");
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static boolean await(List<String> output, String needle, long timeoutSec)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (output.stream().anyMatch(l -> l.contains(needle))) return true;
            Thread.sleep(200);
        }
        return false;
    }

    private static String tail(List<String> output) {
        List<String> lines = new ArrayList<>(output);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
    }
}
