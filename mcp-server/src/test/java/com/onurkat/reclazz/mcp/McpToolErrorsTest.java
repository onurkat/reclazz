/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class McpToolErrorsTest {
    private static final String HELLO = "{\"level\":\"CONNECTED\",\"agent\":\"test\",\"version\":1}";
    @TempDir Path dir;

    private JsonObject request(String tool) {
        JsonObject request = JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"arguments\":{\"className\":\"com.acme.Service\",\"timeoutMs\":\"1000\"}}}").getAsJsonObject();
        request.getAsJsonObject("params").addProperty("name", tool);
        return request;
    }

    private JsonObject call(String tool, int port) {
        JsonObject request = request(tool);
        request.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("port", "" + port);
        return new McpServer().handle(request);
    }

    private static String text(JsonObject response, boolean error) {
        assertFalse(response.has("error"), response.toString());
        JsonObject result = response.getAsJsonObject("result");
        assertEquals(error, result.get("isError").getAsBoolean(), response.toString());
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertFalse(text.isBlank());
        return text;
    }

    @ParameterizedTest
    @ValueSource(strings = {"reclazz_scan", "reclazz_pending", "reclazz_diagnose"})
    void offlineIsAToolError(String tool) throws Exception {
        JsonObject request = request(tool);
        request.getAsJsonObject("params").getAsJsonObject("arguments")
                .addProperty("portFile", dir.resolve("missing.port").toString());
        assertTrue(text(new McpServer().handle(request), true).contains("no port file"));
        try (ServerSocket unused = new ServerSocket()) {
            unused.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = unused.getLocalPort();
            unused.close();
            assertTrue(text(call(tool, port), true).contains("cannot reach"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reclazz_scan", "reclazz_pending", "reclazz_diagnose"})
    void invalidHandshakeNeverSendsCommand(String tool) throws Exception {
        for (String hello : new String[] {
                "{\"level\":\"CONNECTED\"}",
                "{\"level\":\"CONNECTED\",\"version\":1}",
                "{\"level\":\"CONNECTED\",\"agent\":\"test\"}",
                HELLO.replace("\"test\"", "true"), HELLO.replace("\"test\"", "\" \""),
                HELLO.replace(":1", ":0"), HELLO.replace(":1", ":2"),
                HELLO.replace(":1", ":1.5"), HELLO.replace(":1", ":\"1\""),
                HELLO.replace(":1", ":999999999999999999999999"),
                HELLO + " trailing", "[]", "null", "not json",
                "{\"level\":\"INFO\",\"message\":\"unrelated\",\"timestamp\":\"t\"}"}) {
            try (Peer peer = new Peer(hello, (in, out) -> assertNull(in.readLine(), hello))) {
                assertTrue(text(call(tool, peer.port()), true).contains("handshake"), hello);
                peer.verify();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reclazz_pending", "reclazz_diagnose"})
    void missingAnswerIsNotAnEmptyDiagnostic(String tool) throws Exception {
        for (boolean timeout : new boolean[] {false, true}) {
            try (Peer peer = new Peer(HELLO, (in, out) -> {
                assertEquals(command(tool), in.readLine());
                // Keep the connection open until the caller's deadline expires.
                if (timeout) assertNull(in.readLine());
            })) {
                String result = text(call(tool, peer.port()), true);
                assertTrue(result.contains("No INFO response"), result);
                assertFalse(result.contains("Nothing pending"));
                assertFalse(result.contains("No diagnosis for"));
                peer.verify();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reclazz_pending", "reclazz_diagnose"})
    void validDiagnosticPreservesJsonText(String tool) throws Exception {
        String message = "Path C:\\new\\thing; quote \"ok\"\nnext line";
        try (Peer peer = new Peer(" { \"version\" : 1, \"agent\" : \"test\", \"level\" : \"CONNECTED\" } ", (in, out) -> {
            assertEquals(command(tool), in.readLine());
            JsonObject info = new JsonObject();
            info.addProperty("timestamp", "t");
            info.addProperty("message", message);
            info.addProperty("level", "INFO");
            out.println(info);
        })) {
            assertEquals(message, text(call(tool, peer.port()), false));
            peer.verify();
        }
    }

    @Test
    void scanConfirmsDispatchOnlyWithoutWaitingForAReceipt() throws Exception {
        try (Peer peer = new Peer(HELLO, (in, out) -> {
            assertEquals("SCAN", in.readLine());
            assertNull(in.readLine());
        })) {
            String result = text(call("reclazz_scan", peer.port()), false);
            assertTrue(result.contains("Sent SCAN"), result);
            assertTrue(result.contains("not confirmed"), result);
            peer.verify();
        }
    }

    @Test
    void explicitEmptyLedgerIsSuccessful() throws Exception {
        try (Peer peer = new Peer(HELLO, (in, out) -> {
            assertEquals("PENDING", in.readLine());
            out.println("{\"level\":\"INFO\",\"message\":\"Nothing from this session needs a restart.\"}");
        })) {
            assertEquals("Nothing from this session needs a restart.", text(call("reclazz_pending", peer.port()), false));
            peer.verify();
        }
    }

    @Test
    void attachedStatusIncludesReasonWhenHealthIsUnavailable() throws Exception {
        try (Peer peer = new Peer(HELLO, (in, out) -> assertEquals("HEALTH", in.readLine()))) {
            JsonObject status = JsonParser.parseString(text(call("reclazz_status", peer.port()), false)).getAsJsonObject();
            assertTrue(status.get("attached").getAsBoolean());
            assertTrue(status.get("reason").getAsString().contains("No INFO response"));
            assertTrue(status.getAsJsonArray("health").isEmpty());
            peer.verify();
        }
    }

    @Test
    void malformedAnswerIsAToolError() throws Exception {
        try (Peer peer = new Peer(HELLO, (in, out) -> {
            assertEquals("PENDING", in.readLine());
            out.println("{\"level\":\"INFO\",\"message\":false}");
        })) {
            assertTrue(text(call("reclazz_pending", peer.port()), true).contains("Invalid agent response"));
            peer.verify();
        }
    }

    @Test
    void statusStillReportsOfflineAsSuccessfulDiagnostic() {
        JsonObject request = request("reclazz_status");
        request.getAsJsonObject("params").getAsJsonObject("arguments")
                .addProperty("portFile", dir.resolve("missing.port").toString());
        assertTrue(text(new McpServer().handle(request), false).contains("\"attached\":false"));
    }

    @Test
    void packagedStdioSurvivesOverflowHandshakeAndAnswersPing() throws Exception {
        try (Peer peer = new Peer(HELLO.replace(":1", ":999999999999999999999999"),
                (in, out) -> assertNull(in.readLine()))) {
            JsonObject request = request("reclazz_pending");
            request.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("port", "" + peer.port());
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", System.getProperty("reclazz.mcp.releaseJar"))
                    .redirectError(dir.resolve("stderr.txt").toFile()).start();
            try {
                try (OutputStream input = child.getOutputStream()) {
                    input.write((request + "\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), output);
                String[] lines = output.strip().split("\\R");
                assertEquals(2, lines.length, output);
                text(JsonParser.parseString(lines[0]).getAsJsonObject(), true);
                JsonObject ping = JsonParser.parseString(lines[1]).getAsJsonObject();
                assertEquals(2, ping.get("id").getAsInt());
                assertTrue(ping.has("result"));
                peer.verify();
            } finally {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static String command(String tool) {
        return tool.equals("reclazz_pending") ? "PENDING" : "DIAGNOSE com.acme.Service";
    }

    private interface Conversation { void run(BufferedReader in, PrintWriter out) throws Exception; }
    private static class Peer implements AutoCloseable {
        final ServerSocket listener;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Peer(String hello, Conversation conversation) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(5000);
            Thread worker = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    out.println(hello);
                    conversation.run(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)), out);
                    done.complete(null);
                } catch (Throwable failure) { done.completeExceptionally(failure); }
            });
            worker.setDaemon(true);
            worker.start();
        }
        int port() { return listener.getLocalPort(); }
        void verify() throws Exception { done.get(6, TimeUnit.SECONDS); }
        public void close() throws IOException { listener.close(); }
    }
}
