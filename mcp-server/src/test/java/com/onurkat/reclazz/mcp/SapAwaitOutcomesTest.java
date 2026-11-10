/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SapAwaitOutcomesTest {
    @TempDir Path dir;
    // Avoid the older helpers' fixed 43-fixture exporter: SAP results have their own inventory.
    static JsonObject call(McpServer server, JsonObject request, String version) {
        McpResourcesTest.result(server,"initialize",McpResourcesTest.params("protocolVersion",version));
        JsonObject result = server.handle(request).getAsJsonObject("result");
        assertNotNull(result);
        JsonObject data = ReloadVerificationTest.text(result);
        if (version.equals("2025-06-18")) assertEquals(data,result.getAsJsonObject("structuredContent"));
        else assertFalse(result.has("structuredContent"));
        String status = data.get("status").getAsString();
        assertEquals(!Set.of("ready","applied","observed").contains(status),result.get("isError").getAsBoolean());
        return data;
    }
    static JsonObject await(AwaitReloadTest.Fixture f, JsonObject cp) {
        return call(f.server,f.waitFor(cp,false,true),"2025-06-18");
    }
    static void empty(JsonObject data) {
        assertTrue(data.getAsJsonArray("sapOutcomes").isEmpty(),data.toString());
        assertFalse(data.get("sapOutcomesTruncated").getAsBoolean());
    }
    static void export(String label,String tool,JsonObject data) throws Exception {
        SapOperationObservationTest.export(label,SapOperationObservationTest.schema(tool),data);
    }
    @Test void sapSuccessAloneIsTimeoutOrHeldAndNeverSendsVerify() throws Exception {
        for (boolean held : List.of(false,true)) try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint(); empty(cp);
            if (held) f.admission = "paused=false pendingClasses=0 pendingActions=1 buildHold=named";
            for (var sample : SapOperationObservationTest.samples()) f.emit(sample.level(),sample.message());
            JsonObject d = await(f,cp);
            assertEquals(held ? "held" : "timeout",d.get("status").getAsString(),d.toString());
            assertFalse(d.get("reloadConfirmed").getAsBoolean()); assertFalse(d.has("receipt"));
            assertEquals(16,d.getAsJsonArray("sapOutcomes").size()); assertTrue(d.get("sapOutcomesTruncated").getAsBoolean());
            long previous = 0;
            for (JsonElement e : d.getAsJsonArray("sapOutcomes")) {
                JsonObject row = e.getAsJsonObject(); long seq = Long.parseLong(row.get("sequence").getAsString());
                assertTrue(seq > previous); assertTrue(seq <= Long.parseLong(d.get("cursor").getAsString())); previous = seq;
                assertEquals("sap_operation",row.get("type").getAsString());
            }
            assertTrue(f.commands.stream().noneMatch(c -> c.startsWith("VERIFY ")));
            export(held ? "await-held" : "await-timeout","reclazz_await_reload",d);
        }
    }
    @Test void javaApplyKeepsExactReceiptWithInterleavedSapAndNoNeighborAttribution() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            f.emit("OK","ImpEx imported: example.impex");
            f.emit("RELOAD","Reloaded " + AwaitReloadTest.OWNER);
            f.duringVerify = out -> {
                f.duringVerify = ignored -> { };
                AwaitReloadTest.event(out,"OK",SapOperationObservationTest.ITEMS);
            };
            JsonObject d = await(f,cp);
            assertEquals("applied",d.get("status").getAsString()); assertTrue(d.get("reloadConfirmed").getAsBoolean());
            assertEquals(ReloadVerificationTest.HASH,d.get("observedSha256").getAsString());
            assertEquals(2,d.getAsJsonArray("sapOutcomes").size()); assertFalse(d.get("sapOutcomesTruncated").getAsBoolean());
            assertEquals(1,f.commands.stream().filter(c -> c.startsWith("VERIFY ")).count());
            for (JsonElement e : d.getAsJsonArray("sapOutcomes")) assertFalse(e.getAsJsonObject().has("ownerClassName"));
            export("await-applied","reclazz_await_reload",d);
            JsonObject fresh = f.checkpoint(); empty(fresh);
            assertEquals("timeout",await(f,fresh).get("status").getAsString());
        }
    }
    @Test void sapSuccessDoesNotOverrideJavaFailureOrUnverifiedReceipt() throws Exception {
        for (String mode : List.of("compile_error","refused","unavailable","restart_required")) try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint(); f.emit("OK",SapOperationObservationTest.ITEMS);
            switch (mode) {
                case "compile_error" -> { f.emit("ERROR","Compilation failed:"); f.emit("ERROR","  Service.java:1: missing"); }
                case "refused" -> { f.status="failed"; f.emit("ERROR","Hot-swap failed for " + AwaitReloadTest.OWNER + ": refused"); }
                case "restart_required" -> { f.status="unverified"; f.emit("WARN",AwaitReloadTest.OWNER + ": superclass change not applied; restart required. Still extends A instead of B"); }
                default -> { f.status="unverified"; f.emit("RELOAD","Reloaded " + AwaitReloadTest.OWNER); }
            }
            JsonObject request = f.waitFor(cp,mode.equals("compile_error"),true);
            JsonObject d = call(f.server,request,"2025-06-18");
            assertEquals(mode,d.get("status").getAsString(),d.toString()); assertFalse(d.get("reloadConfirmed").getAsBoolean());
            assertEquals(1,d.getAsJsonArray("sapOutcomes").size());
            export("await-" + mode,"reclazz_await_reload",d);
        }
    }
    @Test void invalidIdentityGapAndTransportClearAllSapAnnotations() throws Exception {
        for (String mode : List.of("session","subscription","evicted","future","transport")) try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint(); f.emit("OK","ImpEx imported: example.impex");
            JsonObject req = f.waitFor(cp,false,true), args = req.getAsJsonObject("params").getAsJsonObject("arguments");
            switch(mode) {
                case "session" -> args.addProperty("sessionId","foreign");
                case "subscription" -> args.addProperty("subscriptionId","foreign");
                case "future" -> args.addProperty("afterCursor","999999");
                case "evicted" -> { for (int i=0;i<=EventSubscription.MAX_EVENTS;i++) f.emit("INFO","other"); }
                case "transport" -> { f.acceptsReset=true; f.output.get().println("{bad json"); }
            }
            JsonObject d = call(f.server,req,"2025-06-18");
            assertEquals("unavailable",d.get("status").getAsString(),mode + d); empty(d);
        }
    }
    @Test void evictionDuringVerifyDiscardsAlreadyCollectedSapRows() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            f.emit("OK","ImpEx imported: example.impex");
            f.emit("RELOAD","Reloaded " + AwaitReloadTest.OWNER);
            f.duringVerify = out -> {
                f.duringVerify = ignored -> { };
                for (int i=0;i<=EventSubscription.MAX_EVENTS;i++) AwaitReloadTest.event(out,"INFO","other");
            };
            JsonObject d = await(f,cp);
            assertEquals("unavailable",d.get("status").getAsString());
            assertEquals("cursor_gap",d.get("reasonCode").getAsString()); empty(d);
            assertFalse(d.get("reloadConfirmed").getAsBoolean());
        }
    }
    @Test void typedToolResourcePaginationAndLegacyOutputShareOneRegistry() throws Exception {
        try (var f = new AwaitReloadTest.Fixture();
             var s = new McpServer(McpTarget.parse(new String[]{"--port-file",Files.writeString(dir.resolve("agent.port"),""+f.agent.server.getLocalPort()).toString()},dir))) {
            JsonObject cp = call(s,AwaitReloadTest.request(f.agent.server.getLocalPort(),"checkpoint"),"2025-06-18");
            for (var sample : SapOperationObservationTest.samples()) f.emit(sample.level(),sample.message());
            JsonObject request = RecentEventsTest.request(f.agent.server.getLocalPort(),cp,"limit","100");
            JsonObject all = call(s,request,"2025-06-18");
            assertEquals(SapOperationObservationTest.samples().size(),all.getAsJsonArray("events").size());
            assertEquals(all,McpResourcesTest.read(s,McpResourcesTest.uri(cp,"0")+"&limit=100",null));
            assertEquals(all,call(s,request,"2024-11-05"));
            String cursor = "0"; JsonArray collected = new JsonArray(); boolean resource = true;
            while (Long.parseLong(cursor) < Long.parseLong(all.get("snapshotHead").getAsString())) {
                JsonObject page = resource ? McpResourcesTest.read(s,McpResourcesTest.uri(cp,cursor)+"&limit=2",null)
                        : call(s,RecentEventsTest.request(f.agent.server.getLocalPort(),cp,"sinceCursor",cursor,"limit","2"),"2025-06-18");
                collected.addAll(page.getAsJsonArray("events")); cursor=page.get("nextCursor").getAsString(); resource=!resource;
            }
            assertEquals(all.getAsJsonArray("events"),collected);
            JsonObject filtered = call(s,RecentEventsTest.request(f.agent.server.getLocalPort(),cp,"ownerClassName",AwaitReloadTest.OWNER),"2025-06-18");
            assertTrue(filtered.getAsJsonArray("events").isEmpty());
            assertEquals(all.get("snapshotHead"),filtered.get("nextCursor"));
            assertTrue(f.commands.stream().allMatch(c -> c.startsWith("DOCTOR ")));
            export("events-all","reclazz_events",all); export("resource-all","reclazz_events",all);
            export("await-checkpoint","reclazz_await_reload",cp);
        }
    }
}
