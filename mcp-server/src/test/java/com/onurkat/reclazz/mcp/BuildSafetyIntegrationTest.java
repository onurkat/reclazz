/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class BuildSafetyIntegrationTest {
    // Not @TempDir: on Windows the child JVM's agent keeps class files open past
    // destroyForcibly, and JUnit's temp cleanup then throws IOException. Manage the
    // directory here and delete it best-effort, so a lingering handle cannot fail a
    // test whose assertions already passed.
    Path dir;

    @BeforeEach void createTempDir() throws IOException { dir = Files.createTempDirectory("reclazz-build-safety"); }

    @AfterEach void deleteTempDir() { bestEffortDelete(dir); }

    static void bestEffortDelete(Path root) {
        if (root == null) return;
        for (int attempt = 0; attempt < 5 && Files.exists(root); attempt++) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
            if (!Files.exists(root)) return;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

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
            String failedHash = hash(classes.resolve("A.class"));
            assertNotEquals("applied", verify(port, "A", failedHash).get("status").getAsString());
            assertEquals(0, runWrapper(port, compileCommand(source, classes, 3, false)));
            await(log, "VALUES=3:3");
            String appliedHash = hash(classes.resolve("A.class"));
            var receiptA = awaitReceipt(port, "A", appliedHash, "applied");
            var receiptB = awaitReceipt(port, "B", hash(classes.resolve("B.class")), "applied");
            assertEquals(receiptA.get("sessionId"), receiptB.get("sessionId"));
            assertNotEquals(receiptA.get("requestId"), receiptB.get("requestId"));
            assertEquals(appliedHash, receiptA.get("observedSha256").getAsString());
            assertEquals("mismatch", verify(port, "A", failedHash).get("status").getAsString());

            // A failed later compiler must not turn its new disk bytes into proof.
            assertEquals(1, runWrapper(port, compileCommand(source, classes, 4, true)));
            assertEquals("mismatch", verify(port, "A", hash(classes.resolve("A.class"))).get("status").getAsString());
            assertEquals("applied", verify(port, "A", appliedHash).get("status").getAsString());
            assertFalse(Files.readString(log).contains("VALUES=4:"));

            // A successful compiler may still produce an unsupported reload.
            try (BuildSession session = BuildSession.open(Map.of("portFile", port.toString(), "owner", "integration-build"))) {
                session.signal("started");
                Files.writeString(source.resolve("A.java"),
                        "public class A extends java.util.ArrayList<String> { public int value() { return 5; } }");
                assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                        "-d", classes.toString(), source.resolve("A.java").toString()));
                session.signal("ok");
            }
            var failed = awaitReceipt(port, "A", hash(classes.resolve("A.class")), "unverified");
            assertFalse(failed.get("detail").getAsString().isBlank());
            assertEquals("mismatch", verify(port, "A", appliedHash).get("status").getAsString());
            assertTrue(failed.get("detail").getAsString().contains("superclass"));
            assertTrue(app.isAlive(), "same application JVM remains alive");
        } finally {
            app.destroyForcibly();
            assertTrue(app.waitFor(10, TimeUnit.SECONDS));
        }
    }

    @Test void autoCompileProducesExactByteReceiptsToo() throws Exception {
        Path agent = Path.of(System.getProperty("reclazz.agent.jar"));
        assertTrue(Files.isRegularFile(agent));
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        Path source = Files.createDirectories(dir.resolve("src/main/java"));
        Compiler.compile(source, classes, "A", 1, false);
        Path appSource = source.resolve("App.java");
        Files.writeString(appSource, """
                public class App {
                    public static void main(String[] args) throws Exception {
                        A a = new A();
                        while (true) { System.out.println("VALUE=" + a.value()); Thread.sleep(25); }
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-cp", classes.toString(), "-d", classes.toString(), appSource.toString()));
        Path port = dir.resolve("auto.port"), log = dir.resolve("auto.log");
        Process app = new ProcessBuilder(BuildSafetyTest.java(), "-javaagent:" + agent
                + "=watchDirs=" + classes + ",startupDelaySec=1,debounceMs=100,autoCompile=true,portFile=" + port,
                "-cp", classes.toString(), "App").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            await(log, "VALUE=1"); await(log, "] Watching ");
            Files.writeString(source.resolve("A.java"), "public class A { public int value() { return 8; } }");
            await(log, "VALUE=8");
            var receipt = awaitReceipt(port, "A", hash(classes.resolve("A.class")), "applied");
            assertEquals("A", receipt.get("className").getAsString());
            assertTrue(app.isAlive());
        } finally { app.destroyForcibly(); assertTrue(app.waitFor(10, TimeUnit.SECONDS)); }
    }

    private static String hash(Path file) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }

    private static com.google.gson.JsonObject verify(Path port, String name, String hash) {
        var request = ReloadVerificationTest.request(1, name, hash);
        var args = request.getAsJsonObject("params").getAsJsonObject("arguments");
        args.remove("port"); args.addProperty("portFile", port.toString()); args.addProperty("timeoutMs", "3000");
        var result = new McpServer().handle(request).getAsJsonObject("result");
        var receipt = ReloadVerificationTest.text(result);
        assertEquals(!receipt.get("status").getAsString().equals("applied"), result.get("isError").getAsBoolean());
        return receipt;
    }

    private static com.google.gson.JsonObject awaitReceipt(Path port, String name, String hash, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        com.google.gson.JsonObject receipt;
        do {
            receipt = verify(port, name, hash);
            if (status.equals(receipt.get("status").getAsString())) return receipt;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + status + ": " + receipt);
    }

    private int runWrapper(Path port, List<String> compile) throws Exception {
        String cp = Path.of(BuildMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator
                + Path.of(com.google.gson.JsonObject.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>(List.of(BuildSafetyTest.java(), "-cp", cp,
                BuildMain.class.getName(), "--port-file", port.toString(), "--owner", "integration-build", "--"));
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
