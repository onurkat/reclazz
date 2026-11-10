/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class RecentEventsTest {
    static JsonObject request(int port, JsonObject cp, String... extra) {
        JsonObject r = AwaitReloadTest.request(port, "checkpoint");
        JsonObject p = r.getAsJsonObject("params"), a = new JsonObject();
        p.addProperty("name", "reclazz_events"); p.add("arguments", a);
        a.addProperty("port", "" + port); a.addProperty("timeoutMs", "1000");
        for (String key : List.of("sessionId", "subscriptionId")) a.add(key, cp.get(key));
        a.add("sinceCursor", cp.get("cursor"));
        for (int i = 0; i < extra.length; i += 2) a.addProperty(extra[i], extra[i + 1]);
        return r;
    }
    static JsonObject data(McpServer server, JsonObject request) {
        server.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}").getAsJsonObject());
        JsonObject r = server.handle(request);
        assertFalse(r.has("error"), r.toString());
        JsonObject result = r.getAsJsonObject("result"), d = result.getAsJsonObject("structuredContent");
        assertEquals(d, ReloadVerificationTest.text(result));
        assertEquals(!d.get("status").getAsString().equals("observed"), result.get("isError").getAsBoolean());
        assertFalse(d.get("reloadConfirmed").getAsBoolean());
        JsonObject fixture = new JsonObject();
        JsonObject tools = server.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").getAsJsonObject());
        for (JsonElement t : tools.getAsJsonObject("result").getAsJsonArray("tools"))
            if (t.getAsJsonObject().get("name").getAsString().equals("reclazz_events")) fixture.add("schema", t.getAsJsonObject().get("outputSchema"));
        fixture.add("data", d);
        try {
            var dir = java.nio.file.Path.of("build", "tool-contract-fixtures"); java.nio.file.Files.createDirectories(dir);
            String label = d.get("status").getAsString().equals("unavailable") ? "unavailable"
                    : d.getAsJsonArray("events").isEmpty() ? "empty" : "observed";
            java.nio.file.Files.writeString(dir.resolve("reclazz_events-" + label + ".json"), fixture.toString());
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        return d;
    }
    @Test void checkpointThenListReturnsOrderedEvents() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            f.emit("COMPILE", "Compiled Service.java (1ms)");
            f.emit("RELOAD", "Reloaded " + AwaitReloadTest.OWNER + " (2ms)");
            JsonObject d = data(f.server, request(f.agent.server.getLocalPort(), cp));
            assertEquals("observed", d.get("status").getAsString());
            JsonArray rows = d.getAsJsonArray("events"); assertEquals(2, rows.size());
            assertEquals("Service.java", rows.get(0).getAsJsonObject().get("fileBasename").getAsString());
            assertEquals(AwaitReloadTest.OWNER, rows.get(1).getAsJsonObject().get("ownerClassName").getAsString());
            assertEquals("compile", rows.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("reload", rows.get(1).getAsJsonObject().get("type").getAsString());
            assertTrue(Long.parseLong(rows.get(0).getAsJsonObject().get("sequence").getAsString())
                    < Long.parseLong(rows.get(1).getAsJsonObject().get("sequence").getAsString()));
            assertEquals("2026-11-10T10:00:00Z", rows.get(0).getAsJsonObject().get("timestamp").getAsString());
            assertEquals(d.get("nextCursor"), d.get("snapshotHead"));
            assertFalse(d.get("hasMore").getAsBoolean());
            assertTrue(f.commands.stream().allMatch(c -> c.startsWith("DOCTOR ")), f.commands.toString());
        }
    }
    @Test void limitedPagesAdvanceOnlyThroughLastInspectedEvent() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            for (int i = 1; i <= 6; i++) f.emit(i % 2 == 0 ? "RELOAD" : "INFO", i % 2 == 0 ? "Reloaded " + AwaitReloadTest.OWNER : "other");
            JsonObject d = data(f.server, request(f.agent.server.getLocalPort(), cp, "limit", "2", "level", "RELOAD"));
            assertEquals("4", d.get("nextCursor").getAsString()); assertTrue(d.get("hasMore").getAsBoolean());
            assertEquals("2", d.getAsJsonArray("events").get(0).getAsJsonObject().get("sequence").getAsString());
            assertEquals("6", d.get("snapshotHead").getAsString());
            JsonObject replay = data(f.server, request(f.agent.server.getLocalPort(), cp, "limit", "2", "level", "RELOAD"));
            assertEquals(d, replay);
            JsonObject next = cp.deepCopy(); next.add("cursor", d.get("nextCursor"));
            JsonObject tail = data(f.server, request(f.agent.server.getLocalPort(), next, "limit", "2", "level", "RELOAD"));
            assertEquals(1, tail.getAsJsonArray("events").size()); assertEquals("6", tail.get("nextCursor").getAsString());
            next.add("cursor", tail.get("nextCursor"));
            assertTrue(data(f.server, request(f.agent.server.getLocalPort(), next)).getAsJsonArray("events").isEmpty());
        }
    }
    @Test void filteredEmptyPageAdvancesThroughInspectedHeadAndFiltersAreExactPerEvent() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            f.emit("COMPILE", "Compiled Service.java (1ms)");
            f.emit("RELOAD", "Reloaded " + AwaitReloadTest.OWNER + "Other");
            f.emit("RELOAD", "Reloaded " + AwaitReloadTest.OWNER + " (inner class)");
            f.emit("RELOAD", "Reloaded " + AwaitReloadTest.OWNER);
            JsonObject d = data(f.server, request(f.agent.server.getLocalPort(), cp, "ownerClassName", AwaitReloadTest.OWNER));
            assertEquals(1, d.getAsJsonArray("events").size()); assertEquals("4", d.get("nextCursor").getAsString());
            JsonObject file = data(f.server, request(f.agent.server.getLocalPort(), cp, "fileBasename", "Service.java"));
            assertEquals(1, file.getAsJsonArray("events").size()); assertEquals("4", file.get("nextCursor").getAsString());
            JsonObject both = data(f.server, request(f.agent.server.getLocalPort(), cp, "ownerClassName", AwaitReloadTest.OWNER, "fileBasename", "Service.java"));
            assertTrue(both.getAsJsonArray("events").isEmpty()); assertEquals("4", both.get("nextCursor").getAsString());
            assertFalse(both.get("hasMore").getAsBoolean());
        }
    }
    @Test void futureEvictedAndForeignCursorsFailWithoutPartialPage() throws Exception {
        for (String mode : List.of("future", "evicted", "subscription", "session", "other-client")) {
            try (var f = new AwaitReloadTest.Fixture(); var other = new McpServer()) {
                JsonObject cp = f.checkpoint(), request = request(f.agent.server.getLocalPort(), cp);
                JsonObject a = request.getAsJsonObject("params").getAsJsonObject("arguments");
                if (mode.equals("future")) a.addProperty("sinceCursor", "99999");
                if (mode.equals("subscription")) a.addProperty("subscriptionId", "other");
                if (mode.equals("session")) a.addProperty("sessionId", "other");
                if (mode.equals("evicted")) for (int i = 0; i <= EventSubscription.MAX_EVENTS; i++) f.emit("INFO", "event " + i);
                JsonObject d = data(mode.equals("other-client") ? other : f.server, request);
                assertEquals("unavailable", d.get("status").getAsString(), mode);
                assertEquals(mode.equals("evicted") ? "cursor_evicted" : mode.equals("future") ? "invalid_cursor" : "session_changed", d.get("reasonCode").getAsString());
                assertTrue(d.getAsJsonArray("events").isEmpty());
                assertEquals(a.get("sinceCursor"), d.get("nextCursor")); assertFalse(d.get("hasMore").getAsBoolean());
            }
        }
    }
    @Test void awaitAndEventsShareIdentityWithoutConsumingEachOthersHistory() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            f.emit("RELOAD", "Reloaded " + AwaitReloadTest.OWNER);
            JsonObject before = data(f.server, request(f.agent.server.getLocalPort(), cp));
            JsonObject receipt = AwaitReloadTest.data(f.server, f.waitFor(cp, false, true));
            assertEquals("applied", receipt.get("status").getAsString());
            JsonObject after = data(f.server, request(f.agent.server.getLocalPort(), cp));
            assertEquals(before, after);
            JsonObject fresh = f.checkpoint();
            assertEquals(cp.get("subscriptionId"), fresh.get("subscriptionId"));
            assertTrue(data(f.server, request(f.agent.server.getLocalPort(), fresh)).getAsJsonArray("events").isEmpty());
        }
    }
    @Test void unknownAndLongMessagesRemainVisibleWithExplicitTruncationAndNoControlRows() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            f.emit("HEARTBEAT", ""); f.emit("INFO", "VERIFY_RESULT ignored"); f.emit("INFO", "BUILD_ACK ignored");
            JsonObject e = new JsonObject(); e.addProperty("level", "OK"); e.addProperty("message", "x".repeat(700));
            e.addProperty("timestamp", "invalid"); f.output.get().println(e);
            JsonObject d = data(f.server, request(f.agent.server.getLocalPort(), cp));
            assertEquals(1, d.getAsJsonArray("events").size()); JsonObject row = d.getAsJsonArray("events").get(0).getAsJsonObject();
            assertEquals("unknown", row.get("type").getAsString()); assertTrue(row.get("timestamp").isJsonNull());
            assertEquals(512, row.get("message").getAsString().length()); assertTrue(row.get("messageTruncated").getAsBoolean());
        }
    }
    @Test void invalidInputIsRejectedBeforeOpeningSocket() throws Exception {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()); var server = new McpServer()) {
            JsonObject cp = JsonParser.parseString("{\"sessionId\":\"session\",\"subscriptionId\":\"subscription\",\"cursor\":\"0\"}").getAsJsonObject();
            for (String[] extra : List.of(new String[]{"limit", "0"},new String[]{"limit", "101"},new String[]{"limit", "01"},
                    new String[]{"level", "HEARTBEAT"},new String[]{"sinceCursor", "9223372036854775808"},new String[]{"sinceCursor", "01"},
                    new String[]{"afterCursor", "0"},new String[]{"action", "checkpoint"},new String[]{"fileBasename", "../X.java"},
                    new String[]{"fileBasename", "X\\Y.java"},new String[]{"ownerClassName", "bad name"},new String[]{"subscriptionId", ""})) {
                JsonObject response = server.handle(request(socket.getLocalPort(), cp, extra));
                assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt(), Arrays.toString(extra));
            }
            for (JsonElement invalid : List.of(new JsonPrimitive(2), new JsonArray(), JsonNull.INSTANCE)) {
                JsonObject r = request(socket.getLocalPort(), cp); r.getAsJsonObject("params").getAsJsonObject("arguments").add("limit", invalid);
                assertEquals(-32602, server.handle(r).getAsJsonObject("error").get("code").getAsInt());
            }
            socket.setSoTimeout(100); assertThrows(java.net.SocketTimeoutException.class, socket::accept);
        }
    }
    @Test void arrivalsAfterSnapshotAreNotSkippedAndRecheckDetectsEviction() throws Exception {
        try (var f = new AwaitReloadTest.Fixture();
             var stream = new EventSubscription(Map.of(), f.agent.server.getLocalPort(), new RequestCancellation(), System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3))) {
            f.emit("INFO", "first"); f.emit("INFO", "second"); stream.doctor(Map.of(), System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
            var snapshot = stream.snapshot(0);
            f.emit("INFO", "third"); stream.doctor(Map.of(), System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
            JsonObject page = new JsonObject(); RecentEvents.page(page, snapshot, Map.of("sinceCursor", "0", "limit", "1"));
            assertEquals("1", page.get("nextCursor").getAsString()); assertEquals(2, snapshot.head());
            var next = stream.snapshot(1); assertEquals(List.of(2L, 3L), next.events().stream().map(EventSubscription.Event::sequence).toList());
            for (int i = 0; i < EventSubscription.MAX_EVENTS; i++) f.emit("INFO", "more");
            stream.doctor(Map.of(), System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
            assertEquals("cursor_gap", assertThrows(EventSubscription.Failure.class, () -> stream.recheck(0)).code);
        }
    }
    @Test void timestampCharactersCountTowardRetentionButMissingTimestampsAreNotInvented() throws Exception {
        for (boolean timestamp : List.of(false, true)) {
            try (var f = new AwaitReloadTest.Fixture()) {
                JsonObject cp = f.checkpoint();
                for (int i = 0; i < 1970; i++) {
                    if (timestamp) f.emit("INFO", "x".repeat(510));
                    else {
                        JsonObject row = new JsonObject(); row.addProperty("level", "INFO"); row.addProperty("message", "x".repeat(510));
                        f.output.get().println(row);
                    }
                }
                JsonObject d = data(f.server, request(f.agent.server.getLocalPort(), cp));
                assertEquals(timestamp ? "unavailable" : "observed", d.get("status").getAsString());
                if (timestamp) assertEquals("cursor_evicted", d.get("reasonCode").getAsString());
                else assertTrue(d.getAsJsonArray("events").get(0).getAsJsonObject().get("timestamp").isJsonNull());
            }
        }
    }
    @Test void deadlineAndMalformedRepliesNeverBecomeObservedEmpty() throws Exception {
        for (boolean malformed : List.of(false, true)) {
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                for (int i = 0; i < 2; i++) {
                    String c = in.readLine(); JsonObject d = DoctorTest.evidence(c);
                    d.addProperty("sessionId", "session-test"); DoctorTest.send(out,c,d);
                }
                assertTrue(in.readLine().startsWith("DOCTOR "));
                if (malformed) out.println("{bad json");
                assertNull(in.readLine(), "failed page closes the stream rather than returning a healthy empty page");
            }); var client = new McpServer()) {
                JsonObject cp = AwaitReloadTest.data(client,AwaitReloadTest.request(agent.server.getLocalPort(),"checkpoint"));
                JsonObject d = data(client,request(agent.server.getLocalPort(),cp,"timeoutMs","150"));
                assertEquals("unavailable",d.get("status").getAsString());
                assertEquals(malformed ? "malformed_response" : "response_timeout",d.get("reasonCode").getAsString());
                assertEquals(cp.get("cursor"),d.get("nextCursor")); assertTrue(d.getAsJsonArray("events").isEmpty());
                agent.verify();
            }
        }
    }
}
