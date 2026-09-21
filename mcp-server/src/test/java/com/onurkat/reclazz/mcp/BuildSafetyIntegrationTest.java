/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class BuildSafetyIntegrationTest {
    @TempDir Path dir;

    @Test void partialFailedCompilationStaysOutOfTheRunningJvm() throws Exception {
        Path agent = Path.of(System.getProperty("reclazz.agent.jar"));
        assertTrue(Files.isRegularFile(agent), "real agent jar required, never skip this proof");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path source = Files.createDirectories(dir.resolve("src"));
        Compiler.compile(source, classes, "A", 1, false);
        Compiler.compile(source, classes, "B", 1, false);
        Path appSource = source.resolve("App.java");
        Files.writeString(appSource, """
                public class App {
                    public static void main(String[] args) throws Exception {
                        A a = new A(); B b = new B();
                        while (true) {
                            System.out.println("VALUES=" + a.value() + ":" + b.value());
                            Thread.sleep(25);
                        }
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-cp", classes.toString(), "-d", classes.toString(), appSource.toString()));
        Path port = dir.resolve("agent.port");
        Path log = dir.resolve("app.log");
        Process app = new ProcessBuilder(BuildSafetyTest.java(), "-javaagent:" + agent
                + "=watchDirs=" + classes + ",startupDelaySec=1,debounceMs=100,portFile=" + port,
                "-cp", classes.toString(), "App").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            await(log, "VALUES=1:1");
            await(log, "] Watching 1 director");
            byte[] before = Files.readAllBytes(classes.resolve("A.class"));
            assertEquals(1, runWrapper(port, compileCommand(source, classes, 2, true)));
            assertFalse(Arrays.equals(before, Files.readAllBytes(classes.resolve("A.class"))),
                    "compiler must have replaced A.class before failing B");
            Thread.sleep(1500);
            assertFalse(Files.readString(log).contains("VALUES=2:"), Files.readString(log));
            assertEquals(0, runWrapper(port, compileCommand(source, classes, 3, false)));
            await(log, "VALUES=3:3");
            assertTrue(app.isAlive(), "same application JVM remains alive");
        } finally {
            app.destroyForcibly();
            assertTrue(app.waitFor(10, TimeUnit.SECONDS));
        }
    }

    private int runWrapper(Path port, List<String> compile) throws Exception {
        String cp = Path.of(BuildMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator
                + Path.of(com.google.gson.JsonObject.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>(List.of(BuildSafetyTest.java(), "-cp", cp,
                BuildMain.class.getName(), "--port-file", port.toString(), "--"));
        command.addAll(compile);
        Process wrapper = new ProcessBuilder(command).inheritIO().start();
        try {
            assertTrue(wrapper.waitFor(30, TimeUnit.SECONDS), "build wrapper timed out");
            return wrapper.exitValue();
        } finally {
            wrapper.descendants().forEach(ProcessHandle::destroyForcibly);
            wrapper.destroyForcibly();
        }
    }

    private List<String> compileCommand(Path source, Path classes, int value, boolean fail) {
        return List.of(BuildSafetyTest.java(), "-cp", BuildSafetyTest.testClasspath(), Compiler.class.getName(),
                source.toString(), classes.toString(), "" + value, "" + fail);
    }
    private static void await(Path log, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains(expected)) return;
            Thread.sleep(50);
        }
        fail("Missing " + expected + " in " + Files.readString(log));
    }
    public static class Compiler {
        public static void main(String[] args) throws Exception {
            Path source = Path.of(args[0]), classes = Path.of(args[1]);
            int value = Integer.parseInt(args[2]);
            int first = compile(source, classes, "A", value, false);
            // Keep a real partial .class visible across multiple watcher/debounce ticks.
            Thread.sleep(1500);
            int second = compile(source, classes, "B", value, Boolean.parseBoolean(args[3]));
            System.exit(first != 0 ? first : second);
        }
        static int compile(Path source, Path classes, String name, int value, boolean broken) throws Exception {
            Path file = source.resolve(name + ".java");
            Files.writeString(file, "public class " + name + " { public int value() { return "
                    + (broken ? "missingSymbol" : value) + "; } }");
            return ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), file.toString());
        }
    }
}
