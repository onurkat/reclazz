/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BatchVerificationTest {
    static JsonArray items(String... names) {
        JsonArray a = new JsonArray();
        for (String name : names) {
            JsonObject p = new JsonObject(); p.addProperty("className", name); p.addProperty("sha256", ReloadVerificationTest.HASH); a.add(p);
        }
        return a;
    }
    static JsonObject request(int port, JsonArray items) {
        JsonObject r = ReloadVerificationTest.request(port, "unused", ReloadVerificationTest.HASH);
        r.getAsJsonObject("params").addProperty("name", "reclazz_verify_batch");
        JsonObject a = r.getAsJsonObject("params").getAsJsonObject("arguments");
        a.remove("className"); a.remove("sha256"); a.add("items", items); a.addProperty("timeoutMs", "2000");
        return r;
    }
    static JsonObject call(int port, JsonArray items) { return new McpServer().handle(request(port, items)).getAsJsonObject("result"); }
    static void reply(java.io.PrintWriter out, String cmd, String status) {
        JsonObject r = ReloadVerificationTest.receipt(cmd, status);
        ReloadVerificationTest.send(out, r.get("requestId").getAsString(), r);
    }

    @Test void allAppliedUsesOneConnectionAndPreservesOrderWithoutAtomicClaim() throws Exception {
        try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
            for (String name : List.of("A", "B", "C")) {
                String cmd = in.readLine(); assertEquals(name, cmd.split(" ")[2]); reply(out, cmd, "applied");
            }
            assertNull(in.readLine());
        })) {
            JsonObject result = call(agent.server.getLocalPort(), items("A", "B", "C"));
            assertFalse(result.get("isError").getAsBoolean());
            JsonObject d = ReloadVerificationTest.text(result);
            assertTrue(d.get("allApplied").getAsBoolean()); assertEquals("applied", d.get("status").getAsString());
            assertFalse(d.get("atomic").getAsBoolean()); assertFalse(d.get("reloadConfirmed").getAsBoolean());
            assertEquals("session-test", d.get("sessionId").getAsString());
            assertEquals(3, d.get("requestedCount").getAsInt());
            assertEquals(List.of("A", "B", "C"), d.getAsJsonArray("results").asList().stream().map(e -> e.getAsJsonObject().get("className").getAsString()).toList());
            agent.verify();
        }
    }

    @Test void mixedReceiptsStayPerItemAndDoNotStopAtTheFirstNonApplied() throws Exception {
        List<String> statuses = List.of("applied", "running", "mismatch", "failed", "unverified", "not_observed");
        try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> { for (String s : statuses) reply(out, in.readLine(), s); })) {
            JsonObject result = call(agent.server.getLocalPort(), items("A", "B", "C", "D", "E", "F"));
            JsonObject d = ReloadVerificationTest.text(result);
            assertTrue(result.get("isError").getAsBoolean()); assertFalse(d.get("allApplied").getAsBoolean());
            assertEquals("incomplete", d.get("status").getAsString());
            assertEquals(statuses, d.getAsJsonArray("results").asList().stream().map(e -> e.getAsJsonObject().get("status").getAsString()).toList());
            agent.verify();
        }
    }

    @Test void disconnectMalformedAndSessionChangeRetainEarlierEvidenceButNeverComplete() throws Exception {
        for (String mode : List.of("disconnect", "session", "hash", "garbage")) {
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                reply(out, in.readLine(), "applied");
                String cmd = in.readLine();
                if (mode.equals("disconnect")) return;
                if (mode.equals("garbage")) out.println("invalid-json");
                else {
                    JsonObject r = ReloadVerificationTest.receipt(cmd, "applied");
                    r.addProperty(mode.equals("session") ? "sessionId" : "observedSha256", "wrong");
                    ReloadVerificationTest.send(out, r.get("requestId").getAsString(), r);
                }
                assertNull(in.readLine(), "third item must not be queried after invalid evidence");
            })) {
                JsonObject result = call(agent.server.getLocalPort(), items("A", "B", "C"));
                JsonObject d = ReloadVerificationTest.text(result); assertTrue(result.get("isError").getAsBoolean());
                assertEquals("unavailable", d.get("status").getAsString()); assertFalse(d.get("allApplied").getAsBoolean());
                assertEquals("applied", d.getAsJsonArray("results").get(0).getAsJsonObject().get("status").getAsString());
                for (int i = 1; i < 3; i++) assertEquals("unavailable", d.getAsJsonArray("results").get(i).getAsJsonObject().get("status").getAsString());
                agent.verify();
            }
        }
    }

    @Test void deadlineIsSharedAcrossReceiptsInsteadOfResetPerClass() throws Exception {
        try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
            String a = in.readLine(); Thread.sleep(250); reply(out, a, "applied");
            String next = in.readLine(); assertNotNull(next); Thread.sleep(400); reply(out, next, "applied");
            // The client abandons this second receipt at the shared deadline and closes first, so the
            // late reply above lands on a closed peer: the tail read then sees a clean EOF or a reset,
            // both proving no third command was sent. (Same guard as BatchCancellationTest.)
            try { assertNull(in.readLine()); } catch (SocketException closedByDeadline) { }
        })) {
            JsonObject r = request(agent.server.getLocalPort(), items("A", "B", "C"));
            r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs", "500");
            JsonObject d = ReloadVerificationTest.text(new McpServer().handle(r).getAsJsonObject("result"));
            assertEquals("unavailable", d.get("status").getAsString());
            assertEquals("applied", d.getAsJsonArray("results").get(0).getAsJsonObject().get("status").getAsString());
            assertEquals("unavailable", d.getAsJsonArray("results").get(1).getAsJsonObject().get("status").getAsString());
            agent.verify();
        }
    }

    @Test void invalidWholeListIsRejectedBeforeOpeningAnyConnection() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            List<JsonElement> bad = new ArrayList<>(List.of(JsonNull.INSTANCE, new JsonObject(), new JsonPrimitive("A"), items(), items("A", "A"), items("A", "B\nSCAN")));
            JsonArray tooMany = new JsonArray(); for (int i=0;i<33;i++) tooMany.add(items("C"+i).get(0)); bad.add(tooMany);
            for (String key : List.of("className", "sha256")) for (JsonElement value : List.of(JsonNull.INSTANCE, new JsonObject(), new JsonPrimitive(true), new JsonPrimitive(""))) {
                JsonArray a=items("A","B"); a.get(1).getAsJsonObject().add(key,value); bad.add(a);
            }
            JsonArray extra=items("A"); extra.get(0).getAsJsonObject().addProperty("extra",true);bad.add(extra);
            for (JsonElement value : bad) {
                JsonObject r=request(listener.getLocalPort(),items("A"));r.getAsJsonObject("params").getAsJsonObject("arguments").add("items",value);
                assertEquals(-32602,new McpServer().handle(r).getAsJsonObject("error").get("code").getAsInt(),value.toString());
            }
            listener.setSoTimeout(100);assertThrows(SocketTimeoutException.class,listener::accept);
        }
    }

    @Test void cancellationBeforeConnectDoesNotOpenASocket() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            RequestCancellation cancel = new RequestCancellation(); cancel.cancel();
            JsonObject data = BatchVerification.run(items("A", "B"), Map.of("port", ""+listener.getLocalPort()), cancel);
            assertEquals("unavailable", data.get("status").getAsString());
            assertFalse(data.get("allApplied").getAsBoolean());
            listener.setSoTimeout(100); assertThrows(SocketTimeoutException.class, listener::accept);
        }
    }

    @Test void maximumBatchAndOfflineResultsRemainBounded() throws Exception {
        JsonArray a=new JsonArray();for(int i=0;i<32;i++)a.add(items("C"+i).get(0));
        assertTrue(BatchVerification.valid(a));
        try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{for(int i=0;i<32;i++)reply(out,in.readLine(),"applied");})) {
            assertFalse(call(agent.server.getLocalPort(),a).get("isError").getAsBoolean());agent.verify();
        }
        try(var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            int port=listener.getLocalPort();listener.close();
            JsonObject d=ReloadVerificationTest.text(call(port,a));
            assertEquals(32,d.getAsJsonArray("results").size());assertEquals("unavailable",d.get("status").getAsString());assertEquals("",d.get("sessionId").getAsString());
        }
    }
}
