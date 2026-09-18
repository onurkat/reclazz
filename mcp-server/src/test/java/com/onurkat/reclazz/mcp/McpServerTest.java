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
}
