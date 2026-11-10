/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class McpResourcesTest {
    @TempDir Path dir;
    static JsonObject rpc(String method, JsonObject params) {
        JsonObject r = new JsonObject(); r.addProperty("jsonrpc", "2.0"); r.addProperty("id", 1);
        r.addProperty("method", method); if (params != null) r.add("params", params); return r;
    }
    static JsonObject params(String key, String value) {
        JsonObject p = new JsonObject(); p.addProperty(key, value); return p;
    }
    static JsonObject result(McpServer s, String method, JsonObject p) {
        JsonObject r = s.handle(rpc(method, p)); assertFalse(r.has("error"), r.toString());
        return r.getAsJsonObject("result");
    }
    @Test void discoveryBothVersions() {
        try (var s = new McpServer()) {
            for (String v : List.of("2024-11-05", "2025-06-18")) {
                JsonObject init = result(s, "initialize", params("protocolVersion", v));
                assertEquals(Set.of("tools", "resources", "prompts"), init.getAsJsonObject("capabilities").keySet());
                JsonObject listed = result(s, "resources/list", null);
                assertEquals(Set.of("resources"), listed.keySet());
                assertEquals(List.of("reclazz://status", "reclazz://pending"),
                    listed.getAsJsonArray("resources").asList().stream().map(x -> x.getAsJsonObject().get("uri").getAsString()).toList());
                JsonObject templates = result(s, "resources/templates/list", new JsonObject());
                assertEquals(1, templates.getAsJsonArray("resourceTemplates").size());
                assertEquals("reclazz://events/{session}/{subscription}?since={since}{&limit}",
                    templates.getAsJsonArray("resourceTemplates").get(0).getAsJsonObject().get("uriTemplate").getAsString());
                assertEquals(11, result(s, "tools/list", null).getAsJsonArray("tools").size());
            }
        }
    }
    McpServer selected(int port) throws Exception {
        Path file = Files.writeString(dir.resolve("agent.port"), Integer.toString(port));
        return new McpServer(McpTarget.parse(new String[]{"--port-file", file.toString()}, dir));
    }
    static String uri(JsonObject cp, String cursor) {
        return "reclazz://events/" + cp.get("sessionId").getAsString() + "/" + cp.get("subscriptionId").getAsString() + "?since=" + cursor;
    }
    static JsonObject read(McpServer s, String uri, String label) throws Exception {
        JsonObject r = result(s, "resources/read", params("uri", uri));
        assertEquals(Set.of("contents"), r.keySet()); assertEquals(1, r.getAsJsonArray("contents").size());
        JsonObject row = r.getAsJsonArray("contents").get(0).getAsJsonObject();
        assertEquals(Set.of("uri", "mimeType", "text"), row.keySet());
        assertEquals(uri, row.get("uri").getAsString()); assertEquals("application/json", row.get("mimeType").getAsString());
        JsonObject data = JsonParser.parseString(row.get("text").getAsString()).getAsJsonObject();
        if (label != null) {
            try (var in = new java.io.InputStreamReader(McpServer.class.getResourceAsStream("/tool-output-schemas.json"), java.nio.charset.StandardCharsets.UTF_8)) {
                JsonObject schemas = JsonParser.parseReader(in).getAsJsonObject(), fixture = new JsonObject();
                String tool = uri.startsWith("reclazz://events/") ? "events" : uri.substring("reclazz://".length());
                fixture.add("schema", schemas.get("reclazz_" + tool)); fixture.add("data", data);
                Path out = Path.of("build/resource-contract-fixtures"); Files.createDirectories(out);
                Files.writeString(out.resolve(label + ".json"), fixture.toString());
            }
        }
        return data;
    }
    static void error(McpServer s, JsonObject request, int code) {
        JsonObject r = s.handle(request); assertTrue(r.has("error"), r.toString());
        assertEquals(code, r.getAsJsonObject("error").get("code").getAsInt(), request.toString());
    }
    @Test void invalidSelectorsAndMetadataNeverOpenTheTarget() throws Exception {
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()); var s = selected(listener.getLocalPort())) {
            String event = "reclazz://events/session/sub?since=0";
            for (String uri : List.of("", "relative", "x".repeat(4097), "reclazz://status\n", "reclazz://status?port=123", "reclazz://pending/extra",
                    "reclazz://status#fragment", "reclazz://user@events/session/sub?since=0", "reclazz://events:123/session/sub?since=0",
                    event + "&since=1", event + "&limit=0", event + "&limit=101", event + "&limit=01", event + "&portFile=x",
                    event + "&projectDir=x", event + "&hybrisHome=x", event + "&", event + "&limit=", event + "&limit=1=2",
                    "reclazz://events/s/u?since=01", "reclazz://events/s/u?since=-1", "reclazz://events/s/u?since=9223372036854775808",
                    "reclazz://events/s/u?limit=1", "reclazz://events/s/u?since=0#frag", "reclazz://events/s%2fu/u?since=0",
                    "reclazz://events/../u?since=0", "reclazz://events/s/u/x?since=0", "RECLAZZ://events/s/u?since=0"))
                error(s, rpc("resources/read", params("uri", uri)), -32602);
            for (String uri : List.of("reclazz://unknown", "https://example.invalid/data", "file:///unread"))
                error(s, rpc("resources/read", params("uri", uri)), -32002);
            for (String method : List.of("resources/list", "resources/templates/list", "prompts/list")) {
                error(s, rpc(method, params("cursor", "x")), -32602);
                error(s, rpc(method, params("_meta", "bad")), -32602);
                JsonObject meta = new JsonObject(); meta.add("_meta", new JsonObject());
                assertNotNull(result(s, method, meta));
            }
            for (JsonElement value : List.of(JsonNull.INSTANCE, new JsonPrimitive(1), new JsonArray())) {
                JsonObject p = new JsonObject(); p.add("uri", value); error(s, rpc("resources/read", p), -32602);
                JsonObject request = rpc("resources/read", null); request.add("params", value); error(s, request, -32602);
            }
            JsonObject extra = params("uri", "reclazz://status"); extra.addProperty("port", "1"); error(s, rpc("resources/read", extra), -32602);
            error(s, rpc("resources/read", null), -32602);
            error(s, rpc("resources/subscribe", params("uri", "reclazz://status")), -32601);
            for (String method : List.of("resources/read", "resources/list", "prompts/get")) {
                JsonObject notification = rpc(method, null); notification.remove("id"); notification.add("params", JsonNull.INSTANCE);
                assertNull(s.handle(notification));
            }
            listener.setSoTimeout(100); assertThrows(java.net.SocketTimeoutException.class, listener::accept);
        }
    }
    @Test void statusAndPendingPreserveCanonicalDataAndLegacyText() throws Exception {
        for (String tool : List.of("status", "pending")) {
            try (var listener = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress()); var s = selected(listener.getLocalPort())) {
                var peer = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try { for (int i = 0; i < 3; i++) try (var socket = listener.accept()) {
                        socket.setSoTimeout(3000);
                        var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                        var out = new java.io.PrintWriter(socket.getOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8);
                        out.println("{\"level\":\"CONNECTED\",\"agent\":\"test\",\"version\":1}");
                        assertEquals(tool.equals("status") ? "HEALTH" : "PENDING", in.readLine());
                        AwaitReloadTest.event(out, "INFO", "synthetic observation");
                    }} catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
                });
                result(s, "initialize", params("protocolVersion", "2025-06-18"));
                JsonObject data = read(s, "reclazz://" + tool, tool + (tool.equals("status") ? "-attached" : "-observed"));
                JsonObject queried = s.handle(McpTargetTest.request(tool, Map.of())).getAsJsonObject("result");
                assertEquals(data, queried.getAsJsonObject("structuredContent"));
                result(s, "initialize", params("protocolVersion", "2024-11-05"));
                JsonObject legacy = s.handle(McpTargetTest.request(tool, Map.of())).getAsJsonObject("result");
                assertFalse(legacy.has("structuredContent"));
                assertEquals(tool.equals("pending") ? "synthetic observation" : data.toString(),
                    legacy.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
                peer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        try (var s = new McpServer(McpTarget.parse(new String[]{"--port-file", "absent.port"}, dir))) {
            assertFalse(read(s, "reclazz://status", "status-detached").get("attached").getAsBoolean());
            JsonObject p = read(s, "reclazz://pending", "pending-unavailable");
            assertEquals("unavailable", p.get("status").getAsString()); assertFalse(p.get("complete").getAsBoolean());
        }
    }
    @Test void eventResourceAndToolSharePagesAndFailuresWithoutAnotherSubscription() throws Exception {
        try (var f = new AwaitReloadTest.Fixture(); var s = selected(f.agent.server.getLocalPort())) {
            JsonObject cp = AwaitReloadTest.data(s, AwaitReloadTest.request(f.agent.server.getLocalPort(), "checkpoint"));
            assertTrue(read(s, uri(cp, cp.get("cursor").getAsString()), "events-empty").getAsJsonArray("events").isEmpty());
            for (int i = 0; i < 3; i++) f.emit("RELOAD", "Reloaded " + AwaitReloadTest.OWNER);
            JsonObject first = read(s, uri(cp, "0") + "&limit=1", "events-observed");
            assertEquals("1", first.get("nextCursor").getAsString()); assertTrue(first.get("hasMore").getAsBoolean());
            assertFalse(first.get("reloadConfirmed").getAsBoolean()); assertEquals("received_events", first.get("correlationScope").getAsString());
            JsonObject second = RecentEventsTest.data(s, RecentEventsTest.request(f.agent.server.getLocalPort(), cp, "sinceCursor", "1", "limit", "1"));
            assertEquals("2", second.get("nextCursor").getAsString());
            JsonObject third = read(s, uri(cp, "2"), null); assertEquals("3", third.get("nextCursor").getAsString());
            assertEquals(1, third.getAsJsonArray("events").size());
            JsonObject replay = read(s, uri(cp, "0") + "&limit=1", null); assertEquals(first, replay);
        }
    }
    @Test void staleFutureAndEvictedResourcesFailClosed() throws Exception {
        for (String mode : List.of("future", "session", "subscription", "evicted")) {
            try (var f = new AwaitReloadTest.Fixture(); var s = selected(f.agent.server.getLocalPort())) {
                JsonObject cp = AwaitReloadTest.data(s, AwaitReloadTest.request(f.agent.server.getLocalPort(), "checkpoint"));
                String cursor = mode.equals("future") ? "999999" : "0";
                if (mode.equals("session")) cp.addProperty("sessionId", "other");
                if (mode.equals("subscription")) cp.addProperty("subscriptionId", "other");
                if (mode.equals("evicted")) for (int i = 0; i <= EventSubscription.MAX_EVENTS; i++) f.emit("INFO", "more");
                JsonObject failure = read(s, uri(cp, cursor), "events-unavailable");
                assertEquals("unavailable", failure.get("status").getAsString());
                assertEquals(mode.equals("future") ? "invalid_cursor" : mode.equals("evicted") ? "cursor_evicted" : "session_changed", failure.get("reasonCode").getAsString(), mode);
                assertTrue(failure.getAsJsonArray("events").isEmpty()); assertEquals(cursor, failure.get("nextCursor").getAsString());
            }
        }
    }
    @Test void eventResourceNeverRetargetsAToolOverrideAndRechecksSelectedProject() throws Exception {
        Path a = Files.createDirectories(dir.resolve("A")), b = Files.createDirectories(dir.resolve("B"));
        var working = new java.util.concurrent.atomic.AtomicReference<>(a);
        try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
            String c; while ((c = in.readLine()) != null) {
                assertTrue(c.startsWith("DOCTOR "));
                JsonObject d = DoctorTest.evidence(c); d.addProperty("sessionId", "session-test");
                d.addProperty("workingDirectory", working.get().toString()); DoctorTest.send(out, c, d);
            }
        })) {
            Path port = Files.writeString(dir.resolve("selected.port"), Integer.toString(agent.server.getLocalPort()));
            try (var s = new McpServer(McpTarget.parse(new String[]{"--project-dir", a.toString(), "--port-file", port.toString()}, dir));
                 var other = new McpServer(McpTarget.parse(new String[]{"--port-file", "missing.port"}, dir))) {
                JsonObject cp = AwaitReloadTest.data(s, AwaitReloadTest.request(agent.server.getLocalPort(), "checkpoint"));
                JsonObject foreign = read(other, uri(cp, "0"), null); assertEquals("unavailable", foreign.get("status").getAsString());
                assertTrue(read(s, uri(cp, "0"), null).getAsJsonArray("events").isEmpty());
                working.set(b);
                JsonObject d = read(s, uri(cp, "0"), null); assertEquals("target_mismatch", d.get("reasonCode").getAsString());
            }
            agent.verify();
        }
    }

}
