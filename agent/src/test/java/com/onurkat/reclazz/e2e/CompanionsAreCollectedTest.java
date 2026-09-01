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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every reload builds a class. This is the test that they go away again.
 *
 * <p>A method body lives in a companion class, so reloading one class thirty
 * times builds thirty companions. Anything in the agent that held on to one of
 * them, a cache keyed by version, a MethodHandle kept for the previous
 * dispatch, a list of what has been reloaded, would turn a day of editing into
 * a metaspace exhaustion, and it would do it invisibly: the developer would see
 * an OutOfMemoryError pointing at whatever code happened to load the next
 * class.
 *
 * <p>The JVM's unloaded-class counter is what says they go away, and it is a
 * strong signal precisely because a class is only unloaded when nothing
 * references it. Measured over 30 reloads, it rises one for one with them.
 *
 * <p>What this deliberately does not assert is that memory stops growing. It
 * does grow, and not because of Reclazz: redefining a class costs metaspace
 * that the JVM never returns, since it keeps the previous version around for
 * threads still running the old code. Measured on stock JDK 21 with a bare
 * {@code redefineClasses} loop and no agent involved, 10.6 KB per redefinition,
 * against Reclazz's 8.5 KB per reload. Asserting on the total would be
 * asserting on HotSpot's behaviour; MetaspaceWatch is what tells the developer
 * about it when it starts to matter.
 *
 * <p>Skipped when the agent jar has not been built.
 */
class CompanionsAreCollectedTest {

    private static final int RELOADS = 30;

    /** Unloading is the GC's business, so a few may still be pending at the end. */
    private static final int SLACK = 5;

    @TempDir
    Path tmp;

    @Test
    void everyReloadsCompanionIsCollectedAgain() throws Exception {
        String agentJar = System.getProperty("reclazz.agent.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                agentJar != null && Files.exists(Path.of(agentJar)),
                "agent shadow jar not built — run :agent:shadowJar");

        Path srcDir = Files.createDirectories(tmp.resolve("src/app"));
        Path classesDir = Files.createDirectories(tmp.resolve("classes"));
        Path work = srcDir.resolve("Work.java");

        Files.writeString(work, source(0));
        Files.writeString(srcDir.resolve("App.java"), """
                package app;
                import java.lang.management.*;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Work work = new Work();
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(500);
                            // The companions are only collectable, not
                            // collected, until something asks.
                            System.gc();
                            ClassLoadingMXBean classes =
                                    ManagementFactory.getClassLoadingMXBean();
                            System.out.println("MEM tag=" + work.tag()
                                    + " loaded=" + classes.getLoadedClassCount()
                                    + " unloaded=" + classes.getUnloadedClassCount());
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
            assertTrue(awaitTag(output, 0, 60),
                    () -> "app did not start under the agent:\n" + tail(output));
            long unloadedBefore = readCount(lastMem(output), "unloaded=");

            for (int i = 1; i <= RELOADS; i++) {
                Files.writeString(work, source(i));
                compile(List.of(work), classesDir);
                int version = i;
                assertTrue(awaitTag(output, i, 45),
                        () -> "reload " + version + " never reached the app:\n" + tail(output));
            }
            Thread.sleep(3000);

            String finalState = lastMem(output);
            System.out.println("[diag] " + finalState);
            long unloaded = readCount(finalState, "unloaded=") - unloadedBefore;

            assertTrue(unloaded >= RELOADS - SLACK,
                    () -> "only " + unloaded + " classes were unloaded across " + RELOADS
                            + " reloads, so the agent is holding companions it built: "
                            + finalState);
        } finally {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** Structural on every save, so a companion is built every time. */
    private static String source(int version) {
        return """
                package app;
                public class Work {
                    private final String added = "v%d";
                    public String tag() { return helper(); }
                    private String helper() { return added == null ? "v%d" : added; }
                }
                """.formatted(version, version);
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

    private static long readCount(String line, String key) {
        assertNotNull(line, "the app never reported its class counts");
        int at = line.indexOf(key) + key.length();
        int end = line.indexOf(' ', at);
        return Long.parseLong(end < 0 ? line.substring(at) : line.substring(at, end));
    }

    private static String lastMem(List<String> output) {
        String found = null;
        for (String line : output) {
            if (line.startsWith("MEM ")) found = line;
        }
        return found;
    }

    private static boolean awaitTag(List<String> output, int version, long timeoutSec)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            String line = lastMem(output);
            if (line != null && line.contains("tag=v" + version + " ")) return true;
            Thread.sleep(150);
        }
        return false;
    }

    private static String tail(List<String> output) {
        List<String> lines = new ArrayList<>(output);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()));
    }
}
