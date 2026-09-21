/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReloadVerificationTest {
    static final String HASH = "a".repeat(64);
    static JsonObject request(int port, String name, String hash) {
        JsonObject request = JsonParser.parseString("{\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"reclazz_verify\",\"arguments\":{}}}").getAsJsonObject();
        JsonObject args = request.getAsJsonObject("params").getAsJsonObject("arguments");
        args.addProperty("port", "" + port); args.addProperty("className", name); args.addProperty("sha256", hash);
        args.addProperty("timeoutMs", "300");
        return request;
    }
    static JsonObject receipt(String command, String status) {
        String[] parts = command.split(" "); assertEquals("VERIFY", parts[0]); assertEquals(4, parts.length);
        JsonObject result = new JsonObject();
        result.addProperty("requestId", parts[1]); result.addProperty("sessionId", "session-test");
        result.addProperty("className", parts[2]); result.addProperty("expectedSha256", parts[3]);
        result.addProperty("observedSha256", parts[3]); result.addProperty("status", status);
        result.addProperty("source", "A.class"); result.addProperty("detail", "fixture");
        result.addProperty("completedAt", "2026-09-21T10:00:00Z");
        return result;
    }
    static void send(PrintWriter out, String token, JsonObject receipt) {
        JsonObject event = new JsonObject(); event.addProperty("level", "INFO");
        event.addProperty("message", "VERIFY_RESULT " + token + " " + receipt); out.println(event);
    }
    static JsonObject text(JsonObject result) {
        return JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    }

    @Test void onlyAppliedIsPositiveAndOldBroadcastsCannotMatch() throws Exception {
        for (String status : List.of("applied", "failed", "unverified", "running", "mismatch", "not_observed")) {
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                JsonObject actual = receipt(in.readLine(), status);
                send(out, "stale-token", receipt("VERIFY stale-token A " + HASH, "applied"));
                send(out, actual.get("requestId").getAsString(), actual);
            })) {
                JsonObject result = new McpServer().handle(request(agent.server.getLocalPort(), "A", HASH)).getAsJsonObject("result");
                assertEquals(!status.equals("applied"), result.get("isError").getAsBoolean());
                assertEquals(status, text(result).get("status").getAsString()); agent.verify();
            }
        }
    }

    @Test void malformedOrUncorrelatedReceiptsCannotProveSuccess() throws Exception {
        for (String broken : List.of("requestId", "className", "expectedSha256", "observedSha256", "completedAt", "sessionId", "status", "missing", "type")) {
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                JsonObject actual = receipt(in.readLine(), "applied"); String token = actual.get("requestId").getAsString();
                if (broken.equals("missing")) actual.remove("detail");
                else if (broken.equals("type")) actual.addProperty("observedSha256", 123);
                else actual.addProperty(broken, "");
                send(out, token, actual);
            })) {
                JsonObject result = new McpServer().handle(request(agent.server.getLocalPort(), "A", HASH)).getAsJsonObject("result");
                assertTrue(result.get("isError").getAsBoolean());
                assertEquals("unavailable", text(result).get("status").getAsString()); agent.verify();
            }
        }
    }

    @Test void oldAgentTimeoutOrDisconnectIsUnavailable() throws Exception {
        for (boolean delay : List.of(false, true)) {
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                assertTrue(in.readLine().startsWith("VERIFY "));
                if (delay) Thread.sleep(600);
            })) {
                JsonObject result = new McpServer().handle(request(agent.server.getLocalPort(), "A", HASH)).getAsJsonObject("result");
                assertTrue(result.get("isError").getAsBoolean());
                assertEquals("unavailable", text(result).get("status").getAsString()); agent.verify();
            }
        }
    }

    @Test void invalidArgumentsAreRejectedBeforeConnectingAndToolSchemaIsExplicit() {
        McpServer server = new McpServer();
        for (String bad : List.of("../A", "A\nSCAN", "", "A".repeat(257))) {
            assertEquals(-32602, server.handle(request(1, bad, HASH)).getAsJsonObject("error").get("code").getAsInt());
        }
        for (String bad : List.of("", "A".repeat(64), HASH + "0", "{}")) {
            assertEquals(-32602, server.handle(request(1, "A", bad)).getAsJsonObject("error").get("code").getAsInt());
        }
        JsonObject invalid = request(1, "A", HASH);
        invalid.getAsJsonObject("params").getAsJsonObject("arguments").add("sha256", new JsonObject());
        assertEquals(-32602, server.handle(invalid).getAsJsonObject("error").get("code").getAsInt());
        String tools = server.handle(JsonParser.parseString("{\"id\":1,\"method\":\"tools/list\"}").getAsJsonObject()).toString();
        assertTrue(tools.contains("reclazz_verify")); assertTrue(tools.contains("\"required\":[\"className\",\"sha256\"]"));
    }
}
