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
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A clean build, then an edit, in a running JVM.
 *
 * <p>Deleting the output tree and rebuilding it is a thing developers do
 * several times an hour, and it takes the watch with it: on Linux the watch is
 * registered against the inode, so the key is invalidated permanently. The
 * watcher used to drop it and never look again, which meant every later edit in
 * that tree went unnoticed, and when the tree was the only watched one the loop
 * stopped for the rest of the session after a single line printed during the
 * noisiest part of a build.
 *
 * <p>On a Mac this passes either way, and did before the fix: the JDK has no
 * native file watching there and polls instead, re-reading the directory every
 * cycle and never noticing it left. It is written for the machines where that
 * is not true, which is Linux, which is CI, containers, and the remote
 * development servers the README supports.
 */
class ReloadSurvivesACleanBuildTest {

    private static final long TIMEOUT_SEC = 60;

    @TempDir
    Path tmp;

    @Test
    void anEditAfterTheOutputTreeIsDeletedAndRebuiltStillReloads() throws Exception {
        String agentJar = System.getProperty("reclazz.agent.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                agentJar != null && Files.exists(Path.of(agentJar)),
                "agent shadow jar not built — run :agent:shadowJar");

        Path srcDir = Files.createDirectories(tmp.resolve("src/app"));
        Path classesDir = Files.createDirectories(tmp.resolve("classes"));
        Path work = srcDir.resolve("Work.java");

        Files.writeString(work, source("v1"));
        Files.writeString(srcDir.resolve("App.java"), """
                package app;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Work work = new Work();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(400);
                            System.out.println("SAW=" + work.tag());
                        }
                    }
                }
                """);
        compile(List.of(srcDir.resolve("App.java"), work), classesDir);

        Process app = new ProcessBuilder(javaBinary(),
                "-javaagent:" + agentJar + "=watchDirs=" + classesDir
                        + ",startupDelaySec=1,debounceMs=200",
                "-cp", classesDir.toString(), "app.App")
                .redirectErrorStream(true).start();

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
            assertTrue(await(output, "SAW=v1", TIMEOUT_SEC),
                    () -> "app did not start under the agent:\n" + tail(output));

            // The control: reloading works before anything is deleted, so a
            // failure afterwards is about the deletion and not about the setup.
            Files.writeString(work, source("v2"));
            compile(List.of(work), classesDir);
            assertTrue(await(output, "SAW=v2", TIMEOUT_SEC),
                    () -> "reloading did not work even before the clean:\n" + tail(output));

            // What `mvn clean` and `ant clean` do.
            try (var tree = Files.walk(classesDir)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            assertFalse(Files.exists(classesDir), "the output tree is gone, as after a clean");
            Thread.sleep(1500);

            Files.createDirectories(classesDir);
            Files.writeString(work, source("v3"));
            compile(List.of(srcDir.resolve("App.java"), work), classesDir);

            assertTrue(await(output, "SAW=v3", TIMEOUT_SEC),
                    () -> "the watch did not come back with the directory, so every edit from "
                            + "here on is unnoticed:\n" + tail(output));

            // And it keeps working, rather than the one recovered event being
            // all there is.
            Files.writeString(work, source("v4"));
            compile(List.of(work), classesDir);
            assertTrue(await(output, "SAW=v4", TIMEOUT_SEC),
                    () -> "the first edit after the clean landed and the next did not:\n"
                            + tail(output));
        } finally {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String source(String value) {
        return """
                package app;
                public class Work {
                    public String tag() { return "%s"; }
                }
                """.formatted(value);
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
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()));
    }
}
