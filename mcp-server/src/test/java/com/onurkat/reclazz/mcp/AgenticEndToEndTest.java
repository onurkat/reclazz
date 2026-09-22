/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Consumer-side proof: only installed jars, JDK tools, stdio and live application probes. */
class AgenticEndToEndTest {
    // Not @TempDir: the installed agent keeps the consumer's class files open past
    // destroyForcibly on Windows, and JUnit's temp cleanup would then throw. Delete
    // best-effort so a lingering handle cannot fail a test that already passed.
    Path dir;

    @BeforeEach void createTempDir() throws IOException { dir = Files.createTempDirectory("reclazz-agentic-e2e"); }

    @AfterEach void deleteTempDir() {
        if (dir == null) return;
        for (int attempt = 0; attempt < 5 && Files.exists(dir); attempt++) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
            if (!Files.exists(dir)) return;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    private Path project, source, classes, port, mcpJar;
    private int requestId;
    private long lastCounter;
    private String identity;
    private String protocolVersion;

    @ParameterizedTest
    @ValueSource(strings = {"2024-11-05", "2025-06-18"})
    void packagedWorkflowPreservesJvmAndHoldsFailedBuild(String version) throws Exception {
        protocolVersion = version;
        project = Files.createDirectories(dir.resolve("clean consumer with spaces"));
        source = Files.createDirectories(project.resolve("src/consumer"));
        classes = project.resolve("classes");
        port = project.resolve("agent.port");
        assertFalse(Files.exists(classes));
        assertFalse(Files.exists(port));
        Path install = Files.createDirectories(dir.resolve("installed jars with spaces"));
        Path agent = Files.copy(Path.of(System.getProperty("reclazz.agent.jar")), install.resolve("agent.jar"));
        mcpJar = Files.copy(Path.of(System.getProperty("reclazz.mcp.releaseJar")), install.resolve("mcp.jar"));
        writeService(1);
        Files.writeString(source.resolve("App.java"), """
                package consumer;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Service service = new Service();
                        String nonce = java.util.UUID.randomUUID().toString();
                        var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                        while (in.readLine() != null) {
                            System.out.println("PROBE " + ProcessHandle.current().pid() + " " + nonce
                                + " " + service.next() + " " + service.value());
                            System.out.flush();
                        }
                    }
                }
                """);
        Files.createDirectories(classes);
        List<String> compile = compiler(source.resolve("Service.java"), source.resolve("App.java"));
        assertEquals(0, run(compile, "initial-compile"));
        try (Child app = new Child(List.of(java(), "-javaagent:" + agent
                + "=watchDirs=" + classes + ",startupDelaySec=1,debounceMs=100,portFile=" + port,
                "-cp", classes.toString(), "consumer.App"), "app");
             Child mcp = new Child(List.of(java(), "-jar", mcpJar.toString()), "mcp")) {
            app.await(line -> line.contains("] Watching 1 director"));
            JsonObject params = new JsonObject(); params.addProperty("protocolVersion", protocolVersion);
            var init = rpc(mcp, "initialize", params).getAsJsonObject("result");
            assertEquals(System.getProperty("reclazz.mcp.releaseVersion"),
                    init.getAsJsonObject("serverInfo").get("version").getAsString());
            mcp.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            assertTrue(text(tool(mcp, "reclazz_status", new JsonObject())).get("attached").getAsBoolean());
            JsonObject doctor = text(tool(mcp, "reclazz_doctor", new JsonObject()));
            assertEquals("observed", doctor.get("status").getAsString());
            assertEquals(Long.toString(app.process.pid()), doctor.get("pid").getAsString());
            assertEquals(project.toRealPath(), Path.of(doctor.get("workingDirectory").getAsString()).toRealPath());
            assertTrue(doctor.get("buildOwnershipSupported").getAsBoolean());
            assertTrue(doctor.get("verifySupported").getAsBoolean());
            assertFalse(doctor.get("reloadConfirmed").getAsBoolean());
            probe(app, 1);
            String initialHash = hash();

            // The documented terminal entry point runs a real compiler from the clean project.
            writeService(2);
            assertEquals(0, wrappedCompile(compile, "successful-build"));
            String appliedHash = hash();
            assertNotEquals(initialHash, appliedHash);
            JsonObject applied = awaitApplied(mcp, appliedHash);
            String session = applied.get("sessionId").getAsString();
            assertFalse(session.isBlank());
            assertEquals(session, doctor.get("sessionId").getAsString());
            probe(app, 2);

            // A multi-stage build emits a real new class, then fails a later javac invocation.
            buildSignal(mcp, "started");
            writeService(3);
            assertEquals(0, run(compiler(source.resolve("Service.java")), "partial-compile"));
            String failedHash = hash();
            assertNotEquals(appliedHash, failedHash);
            assertHeld(app, 2);
            Path broken = source.resolve("Broken.java");
            Files.writeString(broken, "package consumer; class Broken { int value = missingSymbol; }");
            assertNotEquals(0, run(compiler(broken), "failed-compile"));
            assertTrue(Files.readString(dir.resolve("failed-compile.stderr")).contains("missingSymbol"));
            buildSignal(mcp, "failed");
            tool(mcp, "reclazz_scan", new JsonObject());
            assertHeld(app, 2);
            var failed = verify(mcp, failedHash);
            assertTrue(failed.get("isError").getAsBoolean(), failed.toString());
            assertNotEquals("applied", text(failed).get("status").getAsString());
            assertEquals(session, text(failed).get("sessionId").getAsString());
            assertEquals(appliedHash, text(verify(mcp, appliedHash)).get("observedSha256").getAsString());

            // Full repaired build releases the hold without replacing the application or its state.
            Files.delete(broken);
            writeService(4);
            assertEquals(0, wrappedCompile(compile, "recovered-build"));
            String recoveredHash = hash();
            JsonObject recovered = awaitApplied(mcp, recoveredHash);
            assertEquals(session, recovered.get("sessionId").getAsString());
            assertNotEquals(applied.get("requestId"), recovered.get("requestId"));
            probe(app, 4);
            JsonObject stale = verify(mcp, failedHash);
            assertTrue(stale.get("isError").getAsBoolean());
            assertEquals("mismatch", text(stale).get("status").getAsString());
            assertTrue(app.process.isAlive());
            assertTrue(mcp.process.isAlive());
        }
    }

    private void writeService(int value) throws Exception {
        Files.writeString(source.resolve("Service.java"), "package consumer; public class Service {"
                + "private int calls; public int next() { return ++calls; }"
                + "public int value() { return " + value + "; }}");
    }

    private List<String> compiler(Path... files) {
        List<String> args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                "-cp", classes.toString(), "-d", classes.toString()));
        for (Path file : files) args.add(file.toString());
        return args;
    }

    private int wrappedCompile(List<String> compile, String name) throws Exception {
        List<String> args = new ArrayList<>(List.of(java(), "-cp", mcpJar.toString(),
                "com.onurkat.reclazz.mcp.BuildMain", "--port-file", port.toString(), "--owner", "workflow", "--"));
        args.addAll(compile);
        return run(args, name);
    }

    private int run(List<String> args, String name) throws Exception {
        try (Child child = new Child(args, name)) {
            child.input.close();
            assertTrue(child.process.waitFor(30, TimeUnit.SECONDS), name + " timed out: " + child.transcript);
            return child.process.exitValue();
        }
    }

    private void probe(Child app, int value) throws Exception {
        app.send("read");
        String line = app.await(s -> s.startsWith("PROBE "));
        String[] fields = line.split(" ");
        assertEquals(5, fields.length, line);
        assertEquals(app.process.pid(), Long.parseLong(fields[1]));
        String now = fields[1] + ":" + fields[2];
        if (identity == null) identity = now;
        assertEquals(identity, now, "application identity changed");
        long counter = Long.parseLong(fields[3]);
        assertTrue(counter > lastCounter, "same Service object's state must survive: " + line);
        lastCounter = counter;
        assertEquals(value, Integer.parseInt(fields[4]), "unexpected live value: " + line);
    }

    private void assertHeld(Child app, int value) throws Exception {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1600);
        do { probe(app, value); Thread.sleep(100); } while (System.nanoTime() < until);
    }

    private JsonObject rpc(Child mcp, String method, JsonObject params) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0"); request.addProperty("id", ++requestId);
        request.addProperty("method", method); request.add("params", params);
        mcp.send(request.toString());
        JsonObject response = JsonParser.parseString(mcp.await(s -> true)).getAsJsonObject();
        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals(requestId, response.get("id").getAsInt(), response.toString());
        assertFalse(response.has("error"), response.toString());
        return response;
    }

    private JsonObject tool(Child mcp, String name, JsonObject args) throws Exception {
        args.addProperty("portFile", port.toString()); args.addProperty("timeoutMs", "3000");
        JsonObject params = new JsonObject(); params.addProperty("name", name); params.add("arguments", args);
        JsonObject result = rpc(mcp, "tools/call", params).getAsJsonObject("result");
        assertEquals(protocolVersion.equals("2025-06-18"), result.has("structuredContent"));
        if (result.has("structuredContent")) {
            assertEquals(result.get("structuredContent"), JsonParser.parseString(result.getAsJsonArray("content")
                    .get(0).getAsJsonObject().get("text").getAsString()));
        }
        return result;
    }

    private void buildSignal(Child mcp, String state) throws Exception {
        JsonObject args = new JsonObject(); args.addProperty("state", state); args.addProperty("owner", "workflow");
        JsonObject result = tool(mcp, "reclazz_build", args);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text")
                .getAsString().contains("Acknowledged BUILD " + state), result.toString());
    }

    private JsonObject verify(Child mcp, String hash) throws Exception {
        JsonObject args = new JsonObject(); args.addProperty("className", "consumer.Service"); args.addProperty("sha256", hash);
        JsonObject result = tool(mcp, "reclazz_verify", args);
        JsonObject receipt = text(result);
        assertEquals("consumer.Service", receipt.get("className").getAsString());
        assertEquals(hash, receipt.get("expectedSha256").getAsString());
        return result;
    }

    private JsonObject awaitApplied(Child mcp, String hash) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        JsonObject result;
        do {
            result = verify(mcp, hash);
            JsonObject receipt = text(result);
            if ("applied".equals(receipt.get("status").getAsString())) {
                assertFalse(result.get("isError").getAsBoolean());
                assertEquals(hash, receipt.get("observedSha256").getAsString());
                assertFalse(receipt.get("completedAt").getAsString().isBlank());
                return receipt;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No applied receipt: " + result);
    }

    private static JsonObject text(JsonObject result) {
        return JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString()).getAsJsonObject();
    }

    private String hash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(classes.resolve("consumer/Service.class"))));
    }

    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }

    private final class Child implements AutoCloseable {
        final Process process;
        final PrintWriter input;
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final StringBuffer transcript = new StringBuffer();
        final Thread reader;
        final Path stderr;

        Child(List<String> args, String name) throws Exception {
            stderr = dir.resolve(name + ".stderr");
            ProcessBuilder builder = new ProcessBuilder(args).directory(project.toFile()).redirectError(stderr.toFile());
            for (String key : List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
                builder.environment().remove(key);
            }
            process = builder.start();
            input = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
            reader = new Thread(() -> {
                try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) { transcript.append(line).append('\n'); lines.add(line); }
                } catch (Exception e) { transcript.append(e); }
            }, "consumer-" + name);
            reader.setDaemon(true); reader.start();
        }

        void send(String line) { input.println(line); assertFalse(input.checkError(), "stdin closed: " + transcript); }

        String await(Predicate<String> matches) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < deadline) {
                String line = lines.poll(100, TimeUnit.MILLISECONDS);
                if (line != null && matches.test(line)) return line;
                if (line == null && !process.isAlive()) break;
            }
            throw new AssertionError("Missing process output. stdout=" + transcript + " stderr=" + Files.readString(stderr));
        }

        @Override public void close() throws Exception {
            input.close();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child cleanup failed");
            reader.join(1000);
            System.out.println("Consumer process " + stderr.getFileName() + "\n" + transcript + Files.readString(stderr));
        }
    }
}
