/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class McpToolContractsTest {
    private static final String MODERN = "2025-06-18";
    private static final String LEGACY = "2024-11-05";
    private static final List<String> TOOLS = List.of("reclazz_status", "reclazz_scan", "reclazz_pending",
            "reclazz_diagnose", "reclazz_build", "reclazz_verify");
    @TempDir Path dir;

    private static JsonObject rpc(String method, JsonObject params) {
        JsonObject r = new JsonObject(); r.addProperty("jsonrpc", "2.0"); r.addProperty("id", 1);
        r.addProperty("method", method); r.add("params", params); return r;
    }
    private static JsonObject init(String version) {
        JsonObject p = new JsonObject(); p.addProperty("protocolVersion", version);
        p.add("capabilities", new JsonObject());
        JsonObject info = new JsonObject(); info.addProperty("name", "contract-test"); info.addProperty("version", "1");
        p.add("clientInfo", info); return rpc("initialize", p);
    }
    private static McpServer client(String version) {
        McpServer s = new McpServer(); s.handle(init(version)); return s;
    }
    private static Map<String, JsonObject> tools(McpServer s) {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        for (JsonElement e : s.handle(rpc("tools/list", new JsonObject())).getAsJsonObject("result").getAsJsonArray("tools"))
            m.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject());
        return m;
    }
    private JsonObject args() {
        JsonObject a = new JsonObject(); a.addProperty("className", "A");
        a.addProperty("sha256", ReloadVerificationTest.HASH); a.addProperty("state", "started");
        a.addProperty("portFile", dir.resolve("missing.port").toString()); a.addProperty("timeoutMs", "500"); return a;
    }
    private static JsonObject call(McpServer s, String tool, JsonObject args) {
        JsonObject p = new JsonObject(); p.addProperty("name", tool); p.add("arguments", args);
        return s.handle(rpc("tools/call", p));
    }
    private JsonObject checked(McpServer s, String tool, JsonObject args, boolean error, String fixture) throws Exception {
        JsonObject response = call(s, tool, args);
        assertFalse(response.has("error"), response.toString());
        JsonObject r = response.getAsJsonObject("result");
        assertEquals(error, r.get("isError").getAsBoolean());
        assertTrue(r.has("structuredContent"), r.toString());
        JsonObject data = r.getAsJsonObject("structuredContent");
        assertEquals(data, JsonParser.parseString(r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()));
        JsonObject exported = new JsonObject(); exported.add("schema", tools(s).get(tool).get("outputSchema"));
        exported.add("data", data);
        Path out = Path.of("build", "tool-contract-fixtures"); Files.createDirectories(out);
        Files.writeString(out.resolve(tool + "-" + fixture + ".json"), exported.toString());
        return data;
    }

    @Test void negotiatesSupportedVersionsAndKeepsSessionsIndependent() {
        McpServer modern = client(MODERN), legacy = client(LEGACY);
        assertEquals(MODERN, modern.handle(init(MODERN)).getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals(LEGACY, legacy.handle(init(LEGACY)).getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals(MODERN, new McpServer().handle(init("2099-01-01")).getAsJsonObject("result").get("protocolVersion").getAsString());
        assertTrue(tools(modern).get("reclazz_status").has("outputSchema"));
        assertFalse(tools(legacy).get("reclazz_status").has("outputSchema"));
        assertFalse(tools(new McpServer()).get("reclazz_status").has("outputSchema"));
        assertTrue(modern.handle(init(" ")).has("error"));
        assertTrue(tools(modern).get("reclazz_status").has("outputSchema"));
    }

    @Test void sixToolsAdvertiseTypedOutputsAndConservativeHints() {
        Map<String, JsonObject> t = tools(client(MODERN)); assertEquals(new HashSet<>(TOOLS), t.keySet());
        for (String name : TOOLS) {
            JsonObject tool = t.get(name);
            assertTrue(tool.has("outputSchema"), name);
            JsonObject schema = tool.getAsJsonObject("outputSchema");
            assertEquals("object", schema.get("type").getAsString());
            assertFalse(schema.getAsJsonArray("required").isEmpty());
            JsonObject hints = tool.getAsJsonObject("annotations");
            boolean mutates = name.equals("reclazz_build") || name.equals("reclazz_scan");
            assertEquals(!mutates, hints.get("readOnlyHint").getAsBoolean(), name);
            assertEquals(mutates, hints.get("openWorldHint").getAsBoolean(), name);
            if (mutates) {
                assertTrue(hints.get("destructiveHint").getAsBoolean());
                assertFalse(hints.get("idempotentHint").getAsBoolean());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"reclazz_status","reclazz_scan","reclazz_pending","reclazz_diagnose","reclazz_build","reclazz_verify"})
    void offlineResultsAreStructuredAndLegacyRemainsText(String name) throws Exception {
        JsonObject data = checked(client(MODERN), name, args(), !name.equals("reclazz_status"), "offline");
        if (name.equals("reclazz_status")) assertFalse(data.get("attached").getAsBoolean());
        else assertEquals("unavailable", data.get("status").getAsString());
        JsonObject old = call(client(LEGACY), name, args()).getAsJsonObject("result");
        assertFalse(old.has("structuredContent"));
        assertFalse(tools(client(LEGACY)).get(name).has("annotations"));
        String text = old.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        if (name.equals("reclazz_scan")) assertTrue(text.startsWith("Scan request failed:"));
        if (name.equals("reclazz_pending")) assertTrue(text.startsWith("Pending query failed:"));
        if (name.equals("reclazz_diagnose")) assertTrue(text.startsWith("Diagnosis query failed:"));
        if (name.equals("reclazz_build")) assertTrue(text.startsWith("Build signal not confirmed:"));
    }

    @ParameterizedTest @ValueSource(strings={"reclazz_status","reclazz_pending","reclazz_diagnose","reclazz_scan"})
    void connectedDiagnosticsAndScanDoNotClaimCompletion(String tool) throws Exception {
        try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
            String command = in.readLine();
            assertEquals(switch (tool) {case "reclazz_status" -> "HEALTH"; case "reclazz_pending" -> "PENDING";
                case "reclazz_scan" -> "SCAN"; default -> "DIAGNOSE A";}, command);
            if (!tool.equals("reclazz_scan")) out.println("{\"level\":\"INFO\",\"message\":\"observed \\\"value\\\"\",\"timestamp\":\"t\"}");
        })) {
            JsonObject a = args(); a.addProperty("port", "" + agent.server.getLocalPort());
            JsonObject data = checked(client(MODERN), tool, a, false, "connected");
            if (tool.equals("reclazz_status")) {
                assertTrue(data.get("attached").getAsBoolean()); assertEquals(1, data.getAsJsonArray("health").size());
            } else if (tool.equals("reclazz_scan")) {
                assertEquals("sent", data.get("status").getAsString()); assertFalse(data.get("reloadConfirmed").getAsBoolean());
            } else {
                assertEquals("observed", data.get("status").getAsString()); assertFalse(data.get("complete").getAsBoolean());
                assertEquals("observed \"value\"", data.getAsJsonArray("lines").get(0).getAsString());
                if (tool.equals("reclazz_diagnose")) assertEquals("A", data.get("className").getAsString());
            }
            agent.verify();
        }
    }

    @Test void buildAckAndMissingAckHaveDifferentMachineStates() throws Exception {
        for (String state : List.of("started","ok","failed")) for (boolean ack : List.of(true,false)) {
            try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
                String command = in.readLine(); assertTrue(command.startsWith("BUILD " + state + " request="));
                if (ack) BuildSafetyTest.ack(out, command);
            })) {
                JsonObject a = args(); a.addProperty("state",state); a.addProperty("port",""+agent.server.getLocalPort());
                JsonObject d = checked(client(MODERN),"reclazz_build",a,!ack,state+"-"+ack);
                assertEquals(ack ? "acknowledged" : "unavailable", d.get("status").getAsString());
                assertEquals(state,d.get("state").getAsString()); assertFalse(d.get("reloadConfirmed").getAsBoolean()); agent.verify();
            }
        }
    }

    @Test void verificationPreservesEveryReceiptStatusAndRejectsUncorrelatedProof() throws Exception {
        for (String status : List.of("applied","running","failed","unverified","mismatch","not_observed","broken")) {
            try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
                JsonObject receipt = ReloadVerificationTest.receipt(in.readLine(), status.equals("broken") ? "applied" : status);
                String token = receipt.get("requestId").getAsString();
                if (status.equals("broken")) receipt.addProperty("observedSha256", "wrong");
                ReloadVerificationTest.send(out,token,receipt);
            })) {
                JsonObject a=args(); a.addProperty("port",""+agent.server.getLocalPort());
                JsonObject d=checked(client(MODERN),"reclazz_verify",a,!status.equals("applied"),status);
                assertEquals(status.equals("broken") ? "unavailable" : status,d.get("status").getAsString());
                assertEquals(ReloadVerificationTest.HASH,d.get("expectedSha256").getAsString());
                if (!status.equals("broken")) {assertEquals("session-test",d.get("sessionId").getAsString()); assertEquals("A.class",d.get("source").getAsString());}
                agent.verify();
            }
        }
    }

    @Test void invalidArgumentsRemainProtocolErrors() {
        JsonObject a=args(); a.addProperty("className","A\nSCAN");
        JsonObject r=call(client(MODERN),"reclazz_diagnose",a);
        assertEquals(-32602,r.getAsJsonObject("error").get("code").getAsInt()); assertFalse(r.has("result"));
    }

    @ParameterizedTest @ValueSource(strings={"2024-11-05","2025-06-18"})
    void packagedSessionUsesNegotiatedContract(String version) throws Exception {
        JsonObject p=new JsonObject(); p.addProperty("name","reclazz_status");p.add("arguments",args());
        String input=init(version)+"\n{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                +rpc("tools/list",new JsonObject())+"\n"+rpc("tools/call",p)+"\n";
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",
                System.getProperty("reclazz.mcp.releaseJar")).redirectError(dir.resolve("stderr").toFile()).start();
        try {
            try(var out=child.getOutputStream()){out.write(input.getBytes(StandardCharsets.UTF_8));}
            // Drain stdout before waiting: a small OS pipe buffer (Windows ~4KB) deadlocks a
            // large modern tools/list against a parent that only reads after waitFor.
            List<String> lines=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8).lines().toList();
            assertTrue(child.waitFor(10,TimeUnit.SECONDS)); assertEquals(0,child.exitValue(),Files.readString(dir.resolve("stderr")));
            assertEquals(3,lines.size());
            assertEquals(version,JsonParser.parseString(lines.get(0)).getAsJsonObject().getAsJsonObject("result").get("protocolVersion").getAsString());
            JsonObject tool=JsonParser.parseString(lines.get(1)).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject();
            JsonObject result=JsonParser.parseString(lines.get(2)).getAsJsonObject().getAsJsonObject("result");
            assertEquals(version.equals(MODERN),tool.has("outputSchema"));
            assertEquals(version.equals(MODERN),result.has("structuredContent"));
        } finally {child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
    }
}
