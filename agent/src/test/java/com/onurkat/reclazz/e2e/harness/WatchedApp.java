/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e.harness;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A real application in a real JVM with the agent attached, for tests that
 * cannot be answered in process.
 *
 * <p>Several tests need the same thing: compile some sources, start a child JVM
 * under {@code -javaagent}, edit a class on disk, and read what the running
 * application says about it. Each of them arrived with its own copy of that,
 * which by six tests was around three hundred lines of the same process
 * plumbing, the same compile call, the same wait loop and the same tail of the
 * output for a failure message. It was written that way once and then copied,
 * which is how it usually goes.
 *
 * <p>What is left in the tests is what each one is actually about. What lives
 * here is what none of them are about.
 */
public final class WatchedApp implements AutoCloseable {

    /** Long enough for a cold JVM under an agent on a loaded machine. */
    public static final long DEFAULT_TIMEOUT_SEC = 60;

    private final Path sourceDir;

    private final Path classesDir;

    private final String classpath;

    private final Process process;

    private final List<String> output = new CopyOnWriteArrayList<>();

    private WatchedApp(Path sourceDir, Path classesDir, String classpath, Process process) {
        this.sourceDir = sourceDir;
        this.classesDir = classesDir;
        this.classpath = classpath;
        this.process = process;
    }

    /**
     * The agent jar, or an assumption failure that skips the test.
     *
     * <p>A plain {@code :agent:test} without the shaded jar cannot run any of
     * these, and skipping says so rather than failing as though the code were
     * broken.
     */
    public static String agentJarOrSkip() {
        String agentJar = System.getProperty("reclazz.agent.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                agentJar != null && Files.exists(Path.of(agentJar)),
                "agent shadow jar not built — run :agent:shadowJar");
        return agentJar;
    }

    public static Builder in(Path tempDir) {
        return new Builder(tempDir);
    }

    public static final class Builder {

        private final Path tempDir;

        private final List<Source> sources = new ArrayList<>();

        private String agentArgs = "startupDelaySec=1,debounceMs=200";

        private String extraClasspath = "";

        private Builder(Path tempDir) {
            this.tempDir = tempDir;
        }

        /** A source file, named without its package or extension. */
        public Builder with(String simpleName, String source) {
            sources.add(new Source(simpleName, source));
            return this;
        }

        /** Agent arguments, without the watched directory, which is added. */
        public Builder agentArgs(String args) {
            this.agentArgs = args;
            return this;
        }

        /** Jars the application needs, beyond its own classes. */
        public Builder classpath(String classpath) {
            this.extraClasspath = classpath;
            return this;
        }

        public WatchedApp start() throws IOException {
            String agentJar = agentJarOrSkip();
            Path sourceDir = Files.createDirectories(tempDir.resolve("src/app"));
            Path classesDir = Files.createDirectories(tempDir.resolve("classes"));

            List<Path> files = new ArrayList<>();
            for (Source source : sources) {
                Path file = sourceDir.resolve(source.name() + ".java");
                Files.writeString(file, source.body());
                files.add(file);
            }
            String classpath = extraClasspath.isEmpty()
                    ? classesDir.toString()
                    : extraClasspath + File.pathSeparator + classesDir;
            compile(files, classesDir, extraClasspath);

            Process process = new ProcessBuilder(javaBinary(),
                    "-javaagent:" + agentJar + "=watchDirs=" + classesDir + "," + agentArgs,
                    "-cp", classpath,
                    "app.App")
                    .redirectErrorStream(true)
                    .start();

            WatchedApp app = new WatchedApp(sourceDir, classesDir, extraClasspath, process);
            app.readOutputInBackground();
            return app;
        }
    }

    private record Source(String name, String body) {
    }

    /** Rewrite one source and compile it into the watched directory. */
    public void rewrite(String simpleName, String source) throws IOException {
        Path file = sourceDir.resolve(simpleName + ".java");
        Files.writeString(file, source);
        compile(List.of(file), classesDir, classpath);
    }

    /** Compile a source that is already on disk, unchanged. */
    public void recompile(String simpleName) throws IOException {
        compile(List.of(sourceDir.resolve(simpleName + ".java")), classesDir, classpath);
    }

    public Path classesDir() {
        return classesDir;
    }

    /** Everything the application has printed so far. */
    public List<String> output() {
        return output;
    }

    /** Waits for a line containing this, and says whether it arrived. */
    public boolean awaits(String needle, long timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (output.stream().anyMatch(line -> line.contains(needle))) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean awaits(String needle) {
        return awaits(needle, DEFAULT_TIMEOUT_SEC);
    }

    /** Waits, and fails with the tail of the output rather than a bare false. */
    public void awaitOrFail(String needle, String whatItMeans) {
        assertTrue(awaits(needle), () -> whatItMeans + ":\n" + tail());
    }

    /** The most recent line containing this, or null. */
    public String latest(String needle) {
        String found = null;
        for (String line : output) {
            if (line.contains(needle)) found = line;
        }
        return found;
    }

    /** The end of the output, which is what a failure message wants. */
    public String tail() {
        List<String> lines = new ArrayList<>(output);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()));
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void readOutputInBackground() {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) output.add(line);
            } catch (Exception stopped) {
                // The process was killed; there is nothing left to read.
            }
        }, "watched-app-output");
        reader.setDaemon(true);
        reader.start();
    }

    private static void compile(List<Path> sources, Path outputDir, String classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required to run this test");
        List<String> args = new ArrayList<>(List.of("-d", outputDir.toString(), "-nowarn"));
        // The output directory is on the compile classpath, the way it is in a
        // real build: recompiling one source has to see the classes already
        // built beside it, or a file that refers to its neighbour fails to
        // build the moment it is edited on its own.
        String full = classpath.isEmpty()
                ? outputDir.toString()
                : classpath + File.pathSeparator + outputDir;
        args.add("-classpath");
        args.add(full);
        sources.forEach(source -> args.add(source.toString()));
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "fixture compilation failed");
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
