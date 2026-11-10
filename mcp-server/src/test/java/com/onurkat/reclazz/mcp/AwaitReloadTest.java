/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AwaitReloadTest {
    static final String OWNER = "com.example.Service";
    static JsonObject request(int port, String action) {
        JsonObject r = JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"reclazz_await_reload\",\"arguments\":{}}}").getAsJsonObject();
        JsonObject a = r.getAsJsonObject("params").getAsJsonObject("arguments");
        a.addProperty("port", "" + port); a.addProperty("action", action); a.addProperty("timeoutMs", "1000");
        return r;
    }
    static JsonObject data(McpServer server, JsonObject request) {
        server.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}").getAsJsonObject());
        JsonObject response = server.handle(request);
        assertFalse(response.has("error"), response.toString());
        JsonObject result = response.getAsJsonObject("result");
        JsonObject data = ReloadVerificationTest.text(result);
        assertEquals(data, result.getAsJsonObject("structuredContent"));
        JsonObject fixture = new JsonObject();
        JsonObject listed = server.handle(JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").getAsJsonObject());
        for (JsonElement tool : listed.getAsJsonObject("result").getAsJsonArray("tools"))
            if (tool.getAsJsonObject().get("name").getAsString().equals("reclazz_await_reload"))
                fixture.add("schema", tool.getAsJsonObject().get("outputSchema"));
        fixture.add("data", data);
        try {
            Path dir = Path.of("build", "tool-contract-fixtures"); Files.createDirectories(dir);
            Files.writeString(dir.resolve("reclazz_await_reload-" + data.get("status").getAsString() + ".json"), fixture.toString());
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        return data;
    }
    static void event(PrintWriter out, String level, String message) {
        JsonObject e = new JsonObject(); e.addProperty("level", level); e.addProperty("message", message);
        e.addProperty("timestamp", "2026-11-10T10:00:00Z"); out.println(e);
    }
    static JsonObject waitRequest(int port, JsonObject checkpoint) {
        JsonObject r = request(port, "wait"), a = r.getAsJsonObject("params").getAsJsonObject("arguments");
        for (String k : new String[]{"sessionId", "subscriptionId"}) a.add(k, checkpoint.get(k));
        a.add("afterCursor", checkpoint.get("cursor"));
        a.addProperty("className", "com.example.Service"); a.addProperty("sha256", ReloadVerificationTest.HASH);
        return r;
    }

    @Test void appliedRequiresAnObservedExactOwnerAndSameSessionReceipt() throws Exception {
        AtomicReference<PrintWriter> stream = new AtomicReference<>();
        try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
            stream.set(out); String command;
            while ((command = in.readLine()) != null) {
                if (command.startsWith("DOCTOR ")) {
                    JsonObject d = DoctorTest.evidence(command); d.addProperty("sessionId", "session-test");
                    DoctorTest.send(out, command, d);
                } else if (command.startsWith("VERIFY ")) {
                    JsonObject receipt = ReloadVerificationTest.receipt(command, "applied");
                    ReloadVerificationTest.send(out, receipt.get("requestId").getAsString(), receipt);
                } else if (command.startsWith("RELOAD status request=")) {
                    event(out, "INFO", "RELOAD_STATE " + command.substring(command.indexOf("request=") + 8)
                            + " status paused=false pendingClasses=0 pendingActions=0 buildHold=none");
                } else fail("Unexpected mutation/command: " + command);
            }
        })) {
            McpServer server = new McpServer();
            try {
                JsonObject checkpoint = data(server, request(agent.server.getLocalPort(), "checkpoint"));
                assertEquals("ready", checkpoint.get("status").getAsString());
                event(stream.get(), "RELOAD", "Reloaded com.example.Service (3ms)");
                JsonObject d = data(server, waitRequest(agent.server.getLocalPort(), checkpoint));
                assertEquals("applied", d.get("status").getAsString(), d.toString());
                assertTrue(d.get("reloadConfirmed").getAsBoolean());
                assertFalse(d.get("sourcePathConfirmed").getAsBoolean());
                assertFalse(d.get("editRevisionConfirmed").getAsBoolean());
            } finally {
                // Reflection keeps this test compilable against the pre-feature baseline.
                try { server.getClass().getMethod("close").invoke(server); }
                catch (NoSuchMethodException baseline) { /* no subscription before feature */ }
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final AtomicReference<PrintWriter> output = new AtomicReference<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        final McpServer server = new McpServer();
        volatile String status = "applied", session = "session-test";
        volatile boolean acceptsReset;
        volatile String admission = "paused=false pendingClasses=0 pendingActions=0 buildHold=none";
        volatile java.util.function.Consumer<PrintWriter> duringVerify = out -> { };
        final BuildSafetyTest.FakeAgent agent;
        Fixture() throws Exception {
            agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                output.set(out); String command;
                try { while ((command = in.readLine()) != null) {
                    commands.add(command);
                    if (command.startsWith("DOCTOR ")) {
                        JsonObject d = DoctorTest.evidence(command); d.addProperty("sessionId", session);
                        DoctorTest.send(out, command, d);
                    } else if (command.startsWith("VERIFY ")) {
                        duringVerify.accept(out);
                        JsonObject r = ReloadVerificationTest.receipt(command, status);
                        r.addProperty("sessionId", session); r.addProperty("observedSha256", ReloadVerificationTest.HASH);
                        if (!command.endsWith(ReloadVerificationTest.HASH) && status.equals("applied")) r.addProperty("status", "mismatch");
                        ReloadVerificationTest.send(out, r.get("requestId").getAsString(), r);
                    } else if (command.startsWith("RELOAD status request=")) {
                        event(out, "INFO", "RELOAD_STATE " + command.substring(command.indexOf("request=") + 8) + " status " + admission);
                    } else fail("Await must never mutate: " + command);
                } } catch (java.net.SocketException reset) { if (!acceptsReset) throw reset; }
            });
        }
        JsonObject checkpoint() { return data(server, request(agent.server.getLocalPort(), "checkpoint")); }
        JsonObject waitFor(JsonObject checkpoint, boolean source, boolean hash) {
            JsonObject r = waitRequest(agent.server.getLocalPort(), checkpoint);
            JsonObject a = r.getAsJsonObject("params").getAsJsonObject("arguments");
            if (source) { a.remove("className"); a.addProperty("filePath", Path.of("build", "synthetic", "Service.java").toAbsolutePath().normalize().toString()); a.addProperty("ownerClassName", OWNER); }
            if (!hash) a.remove("sha256");
            a.addProperty("timeoutMs", "200"); return r;
        }
        void emit(String level, String message) { event(output.get(), level, message); }
        @Override public void close() throws Exception { server.close(); agent.verify(); agent.close(); }
    }

    @Test void sourceModeRequiresCompileBeforeExactOwnerAndDiscoversThenReverifiesHash() throws Exception {
        try (var f = new Fixture()) {
            JsonObject cp = f.checkpoint();
            f.emit("COMPILE", "Compiled Service.java (1ms)");
            f.emit("STRUCTURAL_RELOAD", "Structural reload: " + OWNER + " (+1 method, 2ms): refreshed");
            JsonObject d = data(f.server, f.waitFor(cp, true, false));
            assertEquals("applied", d.get("status").getAsString(), d.toString());
            assertEquals("receipt", d.get("hashSource").getAsString());
            assertEquals(2, f.commands.stream().filter(c -> c.startsWith("VERIFY ")).count());
            assertTrue(f.commands.stream().anyMatch(c -> c.endsWith("0".repeat(64))));
            assertEquals(ReloadVerificationTest.HASH, d.get("observedSha256").getAsString());
        }
    }

    @Test void staleOtherOwnerInnerOnlyWrongFileAndWrongOrderNeverReuseOldReceipt() throws Exception {
        for (String mode : List.of("stale", "other-owner", "inner-display", "inner-binary", "wrong-file", "wrong-order", "no-event")) {
            try (var f = new Fixture()) {
                f.checkpoint();
                if (mode.equals("stale")) { f.emit("COMPILE", "Compiled Service.java (1ms)"); f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)"); }
                JsonObject cp = f.checkpoint();
                switch (mode) {
                    case "other-owner" -> { f.emit("COMPILE", "Compiled Service.java (1ms)"); f.emit("RELOAD", "Reloaded " + OWNER + "Other (1ms)"); }
                    case "inner-display" -> { f.emit("COMPILE", "Compiled Service.java (1ms)"); f.emit("RELOAD", "Reloaded " + OWNER + " (inner class) (1ms)"); }
                    case "inner-binary" -> { f.emit("COMPILE", "Compiled Service.java (1ms)"); f.emit("RELOAD", "Reloaded " + OWNER + "$Inner (1ms)"); }
                    case "wrong-file" -> { f.emit("COMPILE", "Compiled Service.javaOther (1ms)"); f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)"); }
                    case "wrong-order" -> { f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)"); f.emit("COMPILE", "Compiled Service.java (1ms)"); }
                }
                JsonObject d = data(f.server, f.waitFor(cp, true, true));
                assertEquals("timeout", d.get("status").getAsString(), mode + d);
                assertFalse(d.get("reloadConfirmed").getAsBoolean());
                assertEquals(0, f.commands.stream().filter(c -> c.startsWith("VERIFY ")).count(), mode);
            }
        }
    }

    @Test void reloadWithoutAppliedVerifyAndCallerHashMismatchFailClosed() throws Exception {
        for (String status : List.of("failed", "unverified", "mismatch", "not_observed", "running")) {
            try (var f = new Fixture()) {
                f.status = status; JsonObject cp = f.checkpoint();
                f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)");
                JsonObject d = data(f.server, f.waitFor(cp, false, true));
                assertNotEquals("applied", d.get("status").getAsString(), status + d);
                assertFalse(d.get("reloadConfirmed").getAsBoolean());
            }
        }
        try (var f = new Fixture()) {
            JsonObject cp = f.checkpoint(); f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)");
            JsonObject r = f.waitFor(cp, false, true); r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("sha256", "b".repeat(64));
            assertEquals("unavailable", data(f.server, r).get("status").getAsString());
        }
    }

    @Test void exactCompilerErrorHierarchyWarningRefusalAndFrameworkFailureStayDistinct() throws Exception {
        for (String expected : List.of("compile_error", "restart_required", "refused", "unavailable")) {
            try (var f = new Fixture()) {
                JsonObject cp = f.checkpoint();
                if (expected.equals("compile_error")) {
                    f.emit("ERROR", "Compilation failed:"); f.emit("ERROR", "  Service.java:12: cannot find symbol missingSymbol");
                } else {
                    f.emit("COMPILE", "Compiled Service.java (1ms)");
                    if (expected.equals("restart_required")) {
                        f.status = "unverified";
                        f.emit("WARN", OWNER + ": superclass change not applied; restart required. Still extends Object instead of ArrayList. Eligible method bodies will be attempted separately.");
                        f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)");
                    } else if (expected.equals("refused")) {
                        f.status = "failed"; f.emit("ERROR", "Hot-swap failed for " + OWNER + ": typed takes or returns ArrayList");
                    } else {
                        f.status = "unverified"; f.emit("ERROR", "Failed to refresh Spring bean for " + OWNER + ": callback failed");
                        f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)");
                    }
                }
                JsonObject d = data(f.server, f.waitFor(cp, true, true));
                assertEquals(expected, d.get("status").getAsString(), d.toString());
                assertFalse(d.get("reloadConfirmed").getAsBoolean());
            }
        }
    }

    @Test void genericOrWrongDiagnosticCannotBeAssignedToTheTarget() throws Exception {
        for (String diagnostic : List.of("  Other.java:1: missing", "  Service.javaOther:1: missing", "Compilation failed for module", "  Service.java: no line")) {
            try (var f = new Fixture()) {
                JsonObject cp = f.checkpoint(); f.emit("ERROR", "Compilation failed:"); f.emit("ERROR", diagnostic);
                assertEquals("timeout", data(f.server, f.waitFor(cp, true, true)).get("status").getAsString(), diagnostic);
            }
        }
    }

    @Test void holdsAreGlobalObservationsAndNeverSendARelease() throws Exception {
        for (String admission : List.of("paused=true pendingClasses=0 pendingActions=1 buildHold=none",
                "paused=false pendingClasses=1 pendingActions=0 buildHold=named",
                "paused=false pendingClasses=1 pendingActions=0 buildHold=legacy")) {
            try (var f = new Fixture()) {
                f.admission = admission; JsonObject cp = f.checkpoint();
                JsonObject d = data(f.server, f.waitFor(cp, true, true));
                assertEquals("held", d.get("status").getAsString());
                assertFalse(d.get("sourcePathConfirmed").getAsBoolean());
                assertTrue(f.commands.stream().noneMatch(c -> c.startsWith("BUILD ") || c.startsWith("SCAN") || c.startsWith("RELOAD resume")));
            }
        }
    }

    @Test void laterRelevantFailureDuringVerifySupersedesTheReloadEvent() throws Exception {
        try (var f = new Fixture()) {
            JsonObject cp = f.checkpoint(); f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)");
            f.duringVerify = out -> {
                f.duringVerify = ignored -> { }; f.status = "failed";
                event(out, "ERROR", "Hot-swap failed for " + OWNER + ": later save failed");
            };
            JsonObject d = data(f.server, f.waitFor(cp, false, true));
            assertEquals("refused", d.get("status").getAsString());
        }
    }

    @Test void sourceOwnerMissingAndVisibleBasenameAmbiguityAreUnavailable() throws Exception {
        try (var f = new Fixture()) {
            JsonObject cp = f.checkpoint(), r = f.waitFor(cp, true, true);
            r.getAsJsonObject("params").getAsJsonObject("arguments").remove("ownerClassName");
            assertEquals("owner_required", data(f.server, r).get("reasonCode").getAsString());
            f.emit("COMPILE", "Compiled Service.java (1ms)"); f.emit("COMPILE", "Compiled Service.java (2ms)");
            f.emit("RELOAD", "Reloaded " + OWNER + " (1ms)");
            assertEquals("ambiguous_source", data(f.server, f.waitFor(cp, true, true)).get("reasonCode").getAsString());
        }
    }

    @Test void malformedAndContradictoryInputsAreRejectedBeforeNetwork() {
        for (String mode : List.of("extra", "both", "relative", "cursor", "overflow", "action", "hash", "owner", "subscription")) {
            JsonObject cp = new JsonObject(); cp.addProperty("sessionId", "session-test"); cp.addProperty("subscriptionId", "subscription-test"); cp.addProperty("cursor", "0");
            JsonObject r = waitRequest(1, cp), a = r.getAsJsonObject("params").getAsJsonObject("arguments");
            switch (mode) {
                case "extra" -> a.addProperty("unexpected", "x");
                case "both" -> a.addProperty("filePath", "/source/Service.java");
                case "relative" -> { a.remove("className"); a.addProperty("filePath", "Service.java"); }
                case "cursor" -> a.addProperty("afterCursor", "-1");
                case "overflow" -> a.addProperty("afterCursor", "9999999999999999999");
                case "action" -> a.addProperty("action", "checkpoint");
                case "hash" -> a.addProperty("sha256", "ABC");
                case "owner" -> a.addProperty("ownerClassName", OWNER);
                case "subscription" -> a.addProperty("subscriptionId", "");
            }
            try (var server = new McpServer()) {
                assertEquals(-32602, server.handle(r).getAsJsonObject("error").get("code").getAsInt(), mode);
            }
        }
    }
}
