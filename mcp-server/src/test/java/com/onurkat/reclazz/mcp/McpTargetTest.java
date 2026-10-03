/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class McpTargetTest {
    @TempDir Path dir;

    static JsonObject request(String tool, Map<String, String> args) {
        JsonObject r = JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"arguments\":{}}}").getAsJsonObject();
        r.getAsJsonObject("params").addProperty("name", "reclazz_" + tool);
        args.forEach((k,v) -> r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty(k,v));
        if (tool.equals("verify_batch")) r.getAsJsonObject("params").getAsJsonObject("arguments").add("items",
                JsonParser.parseString("[{\"className\":\"app.A\",\"sha256\":\"" + "a".repeat(64) + "\"}]"));
        return r;
    }

    @Test void relativePathsAndEndpointOverridesAreInstanceLocal() throws Exception {
        Path a = Files.createDirectory(dir.resolve("project A")), b = Files.createDirectory(dir.resolve("project B"));
        McpTarget target = McpTarget.parse(new String[]{"--port-file", "custom.port", "--project-dir", "project A"}, dir);
        assertEquals(a.toRealPath().resolve("custom.port").toString(), target.options(Map.of()).get("portFile"));
        var projectOverride = target.options(Map.of("projectDir", "../project B"));
        assertEquals(b.toRealPath().toString(), projectOverride.get("baseDir"));
        assertFalse(projectOverride.containsKey("portFile"));
        for (String key : List.of("port", "portFile", "hybrisHome")) {
            var opts = target.options(Map.of(key, key.equals("port") ? "1234" : "other"));
            if (!key.equals("portFile")) assertFalse(opts.containsKey("portFile"));
            else assertEquals(a.toRealPath().resolve("other").toString(), opts.get(key));
        }
        var other = McpTarget.parse(new String[]{"--project-dir", b.toString()}, dir);
        assertEquals(b.toRealPath().toString(), other.options(Map.of()).get("baseDir"));
        assertEquals(a.toRealPath().toString(), target.options(Map.of()).get("baseDir"));
    }

    @Test void startupAndCallInputRejectAmbiguityAndBlankSelectors() throws Exception {
        for (String[] args : List.of(new String[]{"--unknown"}, new String[]{"--project-dir"},
                new String[]{"--port-file", "--project-dir", "."}, new String[]{"--port-file", "x", "--port-file", "y"},
                new String[]{"--project-dir", "missing"}, new String[]{"--port-file", " "}, new String[]{"--port-file", "a\nb"}))
            assertThrows(IllegalArgumentException.class, () -> McpTarget.parse(args, dir));
        Path file = Files.writeString(dir.resolve("not-directory"), "x");
        assertThrows(IllegalArgumentException.class, () -> McpTarget.parse(new String[]{"--project-dir", file.toString()}, dir));
        var server = new McpServer(McpTarget.parse(new String[0], dir));
        for (String key : List.of("projectDir", "portFile", "hybrisHome", "port")) {
            JsonObject r = server.handle(request("scan", Map.of(key, " ")));
            assertEquals(-32602, r.getAsJsonObject("error").get("code").getAsInt());
        }
    }

    @Test void explicitMissingMalformedOrStaleEndpointsNeverFallBack() throws Exception {
        try (var other = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Path standard = Files.createDirectories(dir.resolve(".reclazz")).resolve("agent.port");
            Files.writeString(standard, "" + other.getLocalPort());
            var target = McpTarget.parse(new String[]{"--port-file", "missing.port"}, dir);
            assertFalse(AgentSocket.run(target.options(Map.of()), "SCAN").connected);
            assertFalse(AgentSocket.run(target.options(Map.of("hybrisHome", "missing-home")), "SCAN").connected);
            Path bad = Files.writeString(dir.resolve("bad.port"), "not-a-port");
            assertFalse(AgentSocket.run(target.options(Map.of("portFile", bad.toString())), "SCAN").connected);
            int stale;
            try (var reserve = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { stale = reserve.getLocalPort(); }
            Files.writeString(bad, "" + stale);
            assertFalse(AgentSocket.run(target.options(Map.of("portFile", bad.toString(), "timeoutMs", "100")), "SCAN").connected);
            assertFalse(AgentSocket.run(target.options(Map.of("port", "65536")), "SCAN").connected);
            other.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class, other::accept);
        }
    }

    @Test void explicitProjectChecksEveryToolBeforeItsCommandOnTheSameSocket() throws Exception {
        Path a = Files.createDirectory(dir.resolve("A")), b = Files.createDirectory(dir.resolve("AB"));
        var target = McpTarget.parse(new String[]{"--project-dir", a.toString()}, dir);
        for (String tool : List.of("status", "scan", "pending", "diagnose", "doctor", "verify", "verify_batch", "build", "reload_control")) {
            try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
                String c = in.readLine(); assertTrue(c.startsWith("DOCTOR "), c);
                JsonObject d = DoctorTest.evidence(c); d.addProperty("workingDirectory", b.toString());
                DoctorTest.send(out, c, d);
                assertNull(in.readLine(), "Must not send the requested " + tool + " after mismatched evidence");
            })) {
                JsonObject r = new McpServer(target).handle(request(tool, Map.of("port", agent.opts().get("port"),
                        "className", "app.A", "sha256", "a".repeat(64), "owner", "test", "state", "started", "action", "pause", "timeoutMs", "300")));
                var result = r.getAsJsonObject("result");
                if (tool.equals("status")) assertFalse(DoctorTest.data(result).get("attached").getAsBoolean());
                else assertTrue(result.get("isError").getAsBoolean(), r.toString());
                agent.verify();
            }
        }
    }

    @Test void explicitProjectFailsClosedOnUnavailableStaleAndInvalidDoctorEvidence() throws Exception {
        for (String mode : List.of("old", "stale", "unavailable", "relative", "missing", "malformed")) {
            try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
                String c = in.readLine();
                JsonObject d = DoctorTest.evidence(c); d.addProperty("workingDirectory", dir.toString());
                switch (mode) {
                    case "old" -> { out.println("{\"level\":\"INFO\",\"message\":\"legacy\"}"); assertNull(in.readLine()); return; }
                    case "stale" -> { c = "DOCTOR stale"; d.addProperty("requestId", "stale"); }
                    case "unavailable" -> d.addProperty("status", "unavailable");
                    case "relative" -> d.addProperty("workingDirectory", ".");
                    case "missing" -> d.remove("workingDirectory");
                    case "malformed" -> { out.println("bad-json"); assertNull(in.readLine()); return; }
                }
                DoctorTest.send(out,c,d); assertNull(in.readLine());
            })) {
                var opts = McpTarget.parse(new String[]{"--project-dir", dir.toString()}, dir)
                        .options(Map.of("port", agent.opts().get("port"), "timeoutMs", "100"));
                assertFalse(AgentSocket.run(opts, "SCAN").connected, mode); agent.verify();
            }
        }
    }

    @Test void intentionalProjectOverrideAndNestedLaunchDirectoryWorkWithoutLeakingDefaults() throws Exception {
        Path a = Files.createDirectory(dir.resolve("A")), b = Files.createDirectory(dir.resolve("B"));
        Path nested = Files.createDirectories(b.resolve("module/run"));
        var target = McpTarget.parse(new String[]{"--project-dir", a.toString(), "--port-file", "missing.port"}, dir);
        try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
            String c = in.readLine(); JsonObject d = DoctorTest.evidence(c); d.addProperty("workingDirectory", nested.toString());
            DoctorTest.send(out,c,d); assertEquals("SCAN", in.readLine());
        })) {
            Files.createDirectories(b.resolve(".reclazz"));
            Files.writeString(b.resolve(".reclazz/agent.port"), agent.opts().get("port"));
            JsonObject result = new McpServer(target).handle(request("scan", Map.of("projectDir", "../B"))).getAsJsonObject("result");
            assertFalse(result.get("isError").getAsBoolean(), result.toString()); agent.verify();
            assertFalse(AgentSocket.run(target.options(Map.of()), "SCAN").connected);
        }
    }

    @Test void receiptConnectionAdmitsMatchingProjectBeforeBuild() throws Exception {
        try (var agent = new BuildSafetyTest.FakeAgent((in,out) -> {
            String c = in.readLine(); JsonObject d = DoctorTest.evidence(c); d.addProperty("workingDirectory", dir.toString());
            DoctorTest.send(out,c,d); c = in.readLine(); assertTrue(c.startsWith("BUILD started "), c); BuildSafetyTest.ack(out,c);
        })) {
            var target = McpTarget.parse(new String[]{"--project-dir", dir.toString()}, dir);
            var result = new McpServer(target).handle(request("build", Map.of("port", agent.opts().get("port"), "owner", "test", "state", "started"))).getAsJsonObject("result");
            assertFalse(result.get("isError").getAsBoolean(), result.toString()); agent.verify();
        }
    }
}
