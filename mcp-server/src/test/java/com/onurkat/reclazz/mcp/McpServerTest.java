/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class McpServerTest {

    @TempDir
    Path dir;

    private final McpServer server = new McpServer();

    private JsonObject req(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void initializeReportsServerInfoAndProtocol() {
        JsonObject r = server.handle(req(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}"));
        JsonObject result = r.getAsJsonObject("result");
        assertEquals("2024-11-05", result.get("protocolVersion").getAsString());
        assertEquals("reclazz-mcp", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
    }

    @Test
    void unsupportedVersionsNegotiateImplementedProtocol() {
        for (String requested : new String[] {"2023-01-01", "2025-03-26", "2099-01-01", "custom-version"}) {
            JsonObject request = req("{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\",\"params\":{}}");
            request.getAsJsonObject("params").addProperty("protocolVersion", requested);
            JsonObject response = server.handle(request);
            assertEquals("init", response.get("id").getAsString());
            JsonObject result = response.getAsJsonObject("result");
            assertEquals("2025-06-18", result.get("protocolVersion").getAsString(), requested);
            assertTrue(result.getAsJsonObject("capabilities").has("tools"));
            assertEquals("reclazz-mcp", result.getAsJsonObject("serverInfo").get("name").getAsString());
        }
    }

    @Test
    void initializeRequiresProtocolVersion() {
        for (String params : new String[] {"", ",\"params\":{}", ",\"params\":null",
                ",\"params\":{\"protocolVersion\":null}", ",\"params\":{\"protocolVersion\":123}",
                ",\"params\":{\"protocolVersion\":{}}", ",\"params\":{\"protocolVersion\":[]}",
                ",\"params\":{\"protocolVersion\":true}", ",\"params\":{\"protocolVersion\":\"\"}",
                ",\"params\":{\"protocolVersion\":\"   \"}"}) {
            JsonObject response = server.handle(req("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\"" + params + "}"));
            assertTrue(response.has("error"), response.toString());
            assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
            assertEquals(7, response.get("id").getAsInt());
        }
    }

    @Test
    void toolsListExposesTheReclazzTools() {
        JsonObject r = server.handle(req("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        String tools = r.getAsJsonObject("result").getAsJsonArray("tools").toString();
        assertTrue(tools.contains("reclazz_status"), tools);
        assertTrue(tools.contains("reclazz_scan"), tools);
        assertTrue(tools.contains("reclazz_pending"), tools);
        assertTrue(tools.contains("reclazz_diagnose"), tools);
    }

    @Test
    void initializedNotificationGetsNoResponse() {
        assertNull(server.handle(req("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
    }

    @Test
    void unknownMethodIsAnError() {
        JsonObject r = server.handle(req("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"no/such\"}"));
        assertEquals(-32601, r.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void diagnoseWithoutClassNameIsAnError() {
        JsonObject r = server.handle(req(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"reclazz_diagnose\",\"arguments\":{}}}"));
        assertEquals(-32602, r.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void statusToolReportsNotAttachedWithNoAgent() {
        // Escape backslashes: on Windows the temp path is C:\... and an
        // unescaped backslash is an invalid JSON string escape.
        String portFile = dir.resolve("missing.port").toString().replace("\\", "\\\\");
        JsonObject r = server.handle(req(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"reclazz_status\",\"arguments\":{\"portFile\":\"" + portFile + "\"}}}"));
        String text = r.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("\"attached\":false"), text);
    }

    @Test
    void statusToolReportsAttachedAgainstAgent() throws Exception {
        try (ServerSocket agent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Path portFile = dir.resolve("agent.port");
            Files.writeString(portFile, String.valueOf(agent.getLocalPort()));

            Thread responder = new Thread(() -> {
                try (Socket s = agent.accept()) {
                    OutputStream out = s.getOutputStream();
                    out.write(("{\"level\":\"CONNECTED\",\"message\":\"hi\",\"timestamp\":\"t\","
                            + "\"version\":1,\"agent\":\"9.9.9\"}\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    if ("HEALTH".equals(in.readLine())) {
                        out.write(("{\"level\":\"INFO\",\"message\":\"Reloads: 3\","
                                + "\"timestamp\":\"t\"}\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    Thread.sleep(400);
                } catch (Exception ignored) {
                    // Client has what it needs.
                }
            });
            responder.setDaemon(true);
            responder.start();

            JsonObject r = server.handle(req(
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"reclazz_status\",\"arguments\":{\"portFile\":\""
                            + portFile.toString().replace("\\", "\\\\") + "\",\"timeoutMs\":\"1500\"}}}"));
            String text = r.getAsJsonObject("result").getAsJsonArray("content")
                    .get(0).getAsJsonObject().get("text").getAsString();
            assertTrue(text.contains("\"attached\":true"), text);
            assertTrue(text.contains("\"agent\":\"9.9.9\""), text);
            assertTrue(text.contains("Reloads: 3"), text);
        }
    }
    @Test
    void invalidPortsDoNotEndStdioSession() throws Exception {
        for (String port : new String[] {"-1", "0", "65536"}) {
            Path portFile = dir.resolve("invalid.port");
            Files.writeString(portFile, port);
            for (boolean fromFile : new boolean[] {false, true}) {
                JsonObject request = req("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"reclazz_status\",\"arguments\":{}}}");
                request.getAsJsonObject("params").getAsJsonObject("arguments").addProperty(
                        fromFile ? "portFile" : "port", fromFile ? portFile.toString() : port);
                String javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                String cp = Path.of(McpMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        + java.io.File.pathSeparator
                        + Path.of(JsonObject.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Process child = new ProcessBuilder(javaCommand, "-cp", cp, McpMain.class.getName())
                        .redirectError(dir.resolve("stderr.txt").toFile()).start();
                try {
                    try (var input = child.getOutputStream()) {
                        input.write((request + "\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "MCP did not exit");
                    String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(0, child.exitValue(), Files.readString(dir.resolve("stderr.txt")));
                    String[] lines = output.strip().split("\\R");
                    assertEquals(2, lines.length, output);
                    assertTrue(lines[0].contains("invalid port"), output);
                    assertEquals(2, req(lines[1]).get("id").getAsInt());
                    assertTrue(req(lines[1]).has("result"), output);
                } finally { child.destroyForcibly(); }
            }
        }
    }

}
