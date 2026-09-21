/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildSafetyTest {
    @TempDir Path dir;

    @Test void compilerWaitsForMatchingReceiptAndReportsItsExit() throws Exception {
        for (int exit : List.of(0, 7)) {
            Path marker = dir.resolve("child-" + exit);
            try (FakeAgent agent = new FakeAgent((in, out) -> {
                String started = in.readLine();
                assertTrue(started.startsWith("BUILD started request="), started);
                ack(out, "BUILD started request=stale");
                Thread.sleep(200);
                assertFalse(Files.exists(marker), "compiler ran before its receipt");
                ack(out, started);
                String finish = in.readLine();
                assertTrue(Files.exists(marker), "finish preceded compiler");
                assertTrue(finish.startsWith("BUILD " + (exit == 0 ? "ok" : "failed") + " request="), finish);
                ack(out, finish);
            })) {
                assertEquals(exit, BuildMain.execute(agent.opts(), child(marker, exit)));
                agent.verify();
            }
        }
    }

    @Test void missingReceiptOrDisconnectNeverStartsCompiler() throws Exception {
        for (boolean disconnect : List.of(true, false)) {
            Path marker = dir.resolve("forbidden-" + disconnect);
            try (FakeAgent agent = new FakeAgent((in, out) -> {
                assertTrue(in.readLine().startsWith("BUILD started"));
                if (!disconnect) Thread.sleep(800);
            })) {
                var opts = agent.opts(); opts.put("timeoutMs", "300");
                assertNotEquals(0, BuildMain.execute(opts, child(marker, 0)));
                assertFalse(Files.exists(marker));
                agent.verify();
            }
        }
    }

    @Test void failedProcessLaunchReportsFailedNotOk() throws Exception {
        try (FakeAgent agent = new FakeAgent((in, out) -> {
            ack(out, in.readLine());
            String finish = in.readLine();
            assertTrue(finish.startsWith("BUILD failed request="), finish);
            ack(out, finish);
        })) {
            assertNotEquals(0, BuildMain.execute(agent.opts(), List.of(dir.resolve("missing-executable").toString())));
            agent.verify();
        }
    }

    @Test void interruptionKeepsTheBuildFailed() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (FakeAgent agent = new FakeAgent((in, out) -> {
            ack(out, in.readLine());
            started.countDown();
            String finish = in.readLine();
            assertTrue(finish.startsWith("BUILD failed request="), finish);
            ack(out, finish);
        })) {
            Path marker = dir.resolve("sleeping");
            CompletableFuture<Integer> result = new CompletableFuture<>();
            Thread builder = new Thread(() -> result.complete(BuildMain.execute(agent.opts(), child(marker, 99))));
            builder.start();
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(marker), "child must really be running before interruption");
                builder.interrupt();
                assertNotEquals(0, result.get(5, TimeUnit.SECONDS));
                agent.verify();
            } finally { builder.interrupt(); builder.join(5000); }
        }
    }

    @Test void lostCompletionReceiptIsNotReportedAsSuccess() throws Exception {
        try (FakeAgent agent = new FakeAgent((in, out) -> {
            ack(out, in.readLine());
            assertTrue(in.readLine().startsWith("BUILD ok request="));
        })) {
            assertNotEquals(0, BuildMain.execute(agent.opts(), child(dir.resolve("ran"), 0)));
            agent.verify();
        }
    }

    @Test void mcpBuildRequiresStateAndAcknowledgement() throws Exception {
        McpServer server = new McpServer();
        for (String state : List.of("null", "{}", "\"nonsense\"", "\"ok\\nSCAN\"")) {
            JsonObject request = request(state, 1);
            assertEquals(-32602, server.handle(request).getAsJsonObject("error").get("code").getAsInt());
        }
        for (boolean receipt : List.of(true, false)) {
            try (FakeAgent agent = new FakeAgent((in, out) -> {
                String command = in.readLine();
                assertTrue(command.startsWith("BUILD started request="));
                if (receipt) ack(out, command);
            })) {
                JsonObject result = server.handle(request("\"started\"", agent.server.getLocalPort()))
                        .getAsJsonObject("result");
                assertEquals(!receipt, result.get("isError").getAsBoolean());
                agent.verify();
            }
        }
        String tools = server.handle(JsonParser.parseString("{\"id\":1,\"method\":\"tools/list\"}")
                .getAsJsonObject()).toString();
        assertTrue(tools.contains("reclazz_build"));
        assertTrue(tools.contains("\"enum\":[\"started\",\"ok\",\"failed\"]"));
    }

    @Test void invalidEndpointAndArgumentsDoNotRunCompiler() {
        Path marker = dir.resolve("never");
        assertNotEquals(0, BuildMain.execute(Map.of("port", "65536"), child(marker, 0)));
        assertEquals(2, BuildMain.run(new String[] {"--"}));
        assertEquals(2, BuildMain.run(new String[] {"--unknown", "x", "--", "x"}));
        assertFalse(Files.exists(marker));
    }

    private static JsonObject request(String state, int port) {
        return JsonParser.parseString("{\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"reclazz_build\",\"arguments\":{\"port\":\"" + port + "\",\"state\":" + state + "}}}")
                .getAsJsonObject();
    }

    static List<String> child(Path marker, int exit) {
        return List.of(java(), "-cp", testClasspath(), Child.class.getName(), marker.toString(), "" + exit);
    }
    static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    static String testClasspath() {
        try { return Path.of(BuildSafetyTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "ran");
            if ("99".equals(args[1])) Thread.sleep(30000);
            System.exit(Integer.parseInt(args[1]));
        }
    }
    static void ack(PrintWriter out, String command) {
        String[] words = command.split(" ");
        out.println("{\"level\":\"INFO\",\"message\":\"BUILD_ACK "
                + words[2].substring("request=".length()) + " " + words[1] + "\"}");
    }
    interface Conversation { void run(BufferedReader in, PrintWriter out) throws Exception; }
    static class FakeAgent implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final CompletableFuture<Void> done = new CompletableFuture<>();
        FakeAgent(Conversation conversation) throws Exception {
            Thread worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    out.println("{\"level\":\"CONNECTED\",\"agent\":\"test\",\"version\":1}");
                    conversation.run(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)), out);
                    done.complete(null);
                } catch (Throwable t) { done.completeExceptionally(t); }
            });
            worker.setDaemon(true); worker.start();
        }
        Map<String,String> opts() { return new HashMap<>(Map.of("port", "" + server.getLocalPort(), "timeoutMs", "3000")); }
        void verify() throws Exception { done.get(6, TimeUnit.SECONDS); }
        public void close() throws IOException { server.close(); }
    }
}
