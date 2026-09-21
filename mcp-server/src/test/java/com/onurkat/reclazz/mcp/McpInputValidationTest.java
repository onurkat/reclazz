/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class McpInputValidationTest {
    @TempDir Path dir;
    private final McpServer server = new McpServer();

    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static JsonObject call(String tool) {
        JsonObject request = object("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"arguments\":{}}}");
        request.getAsJsonObject("params").addProperty("name", tool);
        return request;
    }
    private static JsonObject args(JsonObject request) { return request.getAsJsonObject("params").getAsJsonObject("arguments"); }
    private static void error(JsonObject response, int code) {
        assertNotNull(response);
        assertTrue(response.has("error"), response::toString);
        assertEquals(code, response.getAsJsonObject("error").get("code").getAsInt());
        assertFalse(response.has("result"));
    }

    @Test void malformedEnvelopeHasAnErrorAndPreservesValidIds() {
        for (String value : List.of("{}", "[]", "null", "true", "4")) {
            JsonObject request = object("{\"jsonrpc\":\"2.0\",\"id\":\"request-a\",\"method\":\"ping\"}");
            request.add("method", JsonParser.parseString(value));
            JsonObject response = server.handle(request);
            error(response, -32600);
            assertEquals("request-a", response.get("id").getAsString());
        }
        for (String value : List.of("{}", "[]", "null", "true", "1.5")) {
            JsonObject request = object("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}");
            request.add("id", JsonParser.parseString(value));
            JsonObject response = server.handle(request);
            error(response, -32600);
            assertTrue(response.get("id").isJsonNull());
        }
        error(server.handle(object("{\"id\":1,\"method\":\"ping\"}")), -32600);
        error(server.handle(object("{\"jsonrpc\":2,\"id\":1,\"method\":\"ping\"}")), -32600);
        assertNull(server.handle(object("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
        assertNull(server.handle(object("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":false}")));
        error(server.handle(object("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"notifications/unknown\"}")), -32601);
    }

    @Test void integerIdsRetainTheirValueIncludingDecimalAndExponentNotation() {
        for (String id : List.of("1", "-2", "1.0", "1e2", "123456789012345678901234567890", "\"text-id\"")) {
            JsonObject request = object("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}");
            JsonObject response = server.handle(request);
            assertTrue(response.has("result"), response::toString);
            assertEquals(request.get("id").toString(), response.get("id").toString());
        }
    }

    @Test void malformedParamsAndEveryKnownArgumentAreRejected() {
        for (String value : List.of("[]", "null", "true", "3", "\"text\"")) {
            JsonObject request = call("reclazz_status");
            request.add("params", JsonParser.parseString(value));
            error(server.handle(request), -32602);
            request = call("reclazz_status");
            request.getAsJsonObject("params").add("arguments", JsonParser.parseString(value));
            error(server.handle(request), -32602);
        }
        for (String value : List.of("{}", "[]", "null", "true", "3")) {
            JsonObject request = call("reclazz_status");
            request.getAsJsonObject("params").add("name", JsonParser.parseString(value));
            error(server.handle(request), -32602);
            request = object("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            request.getAsJsonObject("params").add("protocolVersion", JsonParser.parseString(value));
            error(server.handle(request), -32602);
            for (String tool : List.of("status", "scan", "pending", "diagnose", "build", "verify")) {
                for (String key : List.of("port", "portFile", "hybrisHome", "timeoutMs", "className", "sha256", "state")) {
                    request = call("reclazz_" + tool);
                    args(request).addProperty("className", "app.Service");
                    args(request).addProperty("sha256", "a".repeat(64));
                    args(request).addProperty("state", "started");
                    args(request).add(key, JsonParser.parseString(value));
                    error(server.handle(request), -32602);
                }
            }
        }
    }

    @Test void invalidInputCannotConnectOrInjectAnotherCommand() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            for (String name : List.of("app.Service\nBUILD ok", "app.Service\rSCAN", "app.Service\0", "", "a".repeat(257))) {
                JsonObject request = call("reclazz_diagnose");
                args(request).addProperty("port", "" + listener.getLocalPort());
                args(request).addProperty("timeoutMs", "1");
                args(request).addProperty("className", name);
                error(server.handle(request), -32602);
            }
            for (String command : List.of("DIAGNOSE app.Service\nBUILD ok", "SCAN\rPENDING", "SCAN\0", "x".repeat(513))) {
                AgentSocket.Result r = AgentSocket.run(Map.of("port", "" + listener.getLocalPort(), "timeoutMs", "1"), command);
                assertFalse(r.connected);
                assertTrue(r.reason.contains("command"), r.reason);
            }
            listener.setSoTimeout(150);
            assertThrows(SocketTimeoutException.class, listener::accept, "Invalid input opened an agent connection");
        }
    }

    @Test void rejectsUnboundedTimeoutsAndInvalidPaths() {
        for (String timeout : List.of("-1", "0", "60001", "999999999999", "soon", "1.5", "")) {
            JsonObject request = call("reclazz_status");
            args(request).addProperty("timeoutMs", timeout);
            args(request).addProperty("portFile", dir.resolve("missing").toString());
            error(server.handle(request), -32602);
        }
        for (String key : List.of("portFile", "hybrisHome")) {
            for (String value : List.of("bad\0path", "bad\npath", "a".repeat(4097))) {
                JsonObject request = call("reclazz_status");
                args(request).addProperty(key, value);
                error(server.handle(request), -32602);
            }
        }
    }

    @Test void validUnicodeInnerClassAndSpacedPortPathStillWork() throws Exception {
        try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
            assertEquals("DIAGNOSE app.Örnek$Inner", in.readLine());
            out.println("{\"level\":\"INFO\",\"message\":\"diagnosed\",\"timestamp\":1}");
        })) {
            Path port = dir.resolve("path with spaces.port");
            Files.writeString(port, agent.opts().get("port"));
            JsonObject request = call("reclazz_diagnose");
            args(request).addProperty("className", "app.Örnek$Inner");
            args(request).addProperty("portFile", port.toString());
            JsonObject result = server.handle(request);
            assertTrue(result.has("result"), result::toString);
            assertTrue(result.toString().contains("diagnosed"), result::toString);
            agent.verify();
        }
    }

    @Test void packagedProcessAnswersPingAfterEachInvalidFrame() throws Exception {
        List<String> frames = List.of("{", "{unquoted:1}", "null", "[]", "true",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"reclazz_status\",\"arguments\":{\"port\":{}}}}",
                "x".repeat(65537));
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            input.append(frames.get(i)).append('\n');
            input.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i + 100).append(",\"method\":\"ping\"}\n");
        }
        String atLimit = "{\"jsonrpc\":\"2.0\",\"id\":200,\"method\":\"ping\"}";
        input.append(atLimit).append(" ".repeat(65536 - atLimit.length())).append('\n');
        input.append("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\r\n");
        input.append("{\"jsonrpc\":\"2.0\",\"id\":201,\"method\":\"ping\"}"); // EOF without newline
        Path inputFile = dir.resolve("input");
        Path outputFile = dir.resolve("output");
        Path errors = dir.resolve("stderr");
        Files.writeString(inputFile, input);
        Process child = new ProcessBuilder(BuildSafetyTest.java(), "-jar", System.getProperty("reclazz.mcp.releaseJar"))
                .redirectInput(inputFile.toFile()).redirectOutput(outputFile.toFile()).redirectError(errors.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "MCP hung");
            assertEquals(0, child.exitValue(), Files.readString(errors));
            List<String> lines = Files.readAllLines(outputFile);
            assertEquals(frames.size() * 2 + 2, lines.size(), lines.toString());
            for (int i = 0; i < 2; i++) {
                JsonObject ping = object(lines.get(frames.size() * 2 + i));
                assertEquals(200 + i, ping.get("id").getAsInt());
                assertTrue(ping.has("result"));
            }
            for (int i = 0; i < frames.size(); i++) {
                JsonObject failure = object(lines.get(i * 2));
                error(failure, i < 2 ? -32700 : i == 6 ? -32602 : -32600);
                assertTrue(failure.has("id"));
                JsonObject ping = object(lines.get(i * 2 + 1));
                assertEquals(i + 100, ping.get("id").getAsInt());
                assertTrue(ping.has("result"));
            }
        } finally { child.destroyForcibly(); }
    }
}
