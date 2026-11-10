/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class EventSubscriptionTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

    @Test void unsupportedAndUncorrelatedDoctorEvidenceCannotCreateAReadySubscription() throws Exception {
        for (String mode : List.of("old", "unsupported", "token")) {
            try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
                String command = in.readLine(); assertTrue(command.startsWith("DOCTOR "));
                if (mode.equals("old")) AwaitReloadTest.event(out, "INFO", "legacy health observation");
                else {
                    JsonObject d = DoctorTest.evidence(command);
                    if (mode.equals("unsupported")) d.addProperty("verifySupported", false);
                    if (mode.equals("token")) d.addProperty("requestId", "stale-token");
                    DoctorTest.send(out, command, d);
                }
                assertNull(in.readLine(), "Unsupported evidence cannot cause VERIFY or mutation commands");
            }); var client = new McpServer()) {
                JsonObject request = AwaitReloadTest.request(agent.server.getLocalPort(), "checkpoint");
                request.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs", "100");
                JsonObject d = AwaitReloadTest.data(client, request);
                assertEquals("unavailable", d.get("status").getAsString());
                assertFalse(d.get("reloadConfirmed").getAsBoolean()); agent.verify();
            }
        }
    }

    @Test void checkpointDeadlineIsSharedAcrossBothDoctorExchanges() throws Exception {
        try (var agent = new BuildSafetyTest.FakeAgent((in, out) -> {
            for (int i = 0; i < 2; i++) {
                String command = in.readLine();
                if (command == null) { assertEquals(1, i, "first DOCTOR must be sent"); return; }
                Thread.sleep(150); DoctorTest.send(out, command, DoctorTest.evidence(command));
            }
            // The second delayed response can meet a closed socket as EOF or reset.
            try { assertNull(in.readLine()); } catch (java.net.SocketException closedByDeadline) { }
        }); var client = new McpServer()) {
            JsonObject request = AwaitReloadTest.request(agent.server.getLocalPort(), "checkpoint");
            request.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs", "250");
            JsonObject d = AwaitReloadTest.data(client, request);
            assertEquals("unavailable", d.get("status").getAsString());
            assertTrue(Set.of("response_timeout", "deadline").contains(d.get("reasonCode").getAsString()), d.toString()); agent.verify();
        }
    }

    @Test void changedPortFileAndIdleEvictionRequireANewSubscriptionEvenForTheSameAgentUuid() throws Exception {
        try (var first = new AwaitReloadTest.Fixture(); var second = new AwaitReloadTest.Fixture();
             var third = new AwaitReloadTest.Fixture(); var client = new McpServer()) {
            java.nio.file.Path port = java.nio.file.Files.writeString(dir.resolve("agent.port"), "" + first.agent.server.getLocalPort());
            JsonObject one = AwaitReloadTest.request(first.agent.server.getLocalPort(), "checkpoint");
            one.getAsJsonObject("params").getAsJsonObject("arguments").remove("port");
            one.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("portFile", port.toString());
            JsonObject cp1 = AwaitReloadTest.data(client, one);
            java.nio.file.Files.writeString(port, "" + second.agent.server.getLocalPort());
            JsonObject stale = AwaitReloadTest.waitRequest(second.agent.server.getLocalPort(), cp1);
            stale.getAsJsonObject("params").getAsJsonObject("arguments").remove("port");
            stale.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("portFile", port.toString());
            assertEquals("session_changed", AwaitReloadTest.data(client, stale).get("reasonCode").getAsString());
            JsonObject cp2 = AwaitReloadTest.data(client, one);
            assertEquals(cp1.get("sessionId"), cp2.get("sessionId"));
            assertNotEquals(cp1.get("subscriptionId"), cp2.get("subscriptionId"));
            AwaitReloadTest.data(client, AwaitReloadTest.request(third.agent.server.getLocalPort(), "checkpoint"));
            assertEquals("session_changed", AwaitReloadTest.data(client, AwaitReloadTest.waitRequest(first.agent.server.getLocalPort(), cp1)).get("reasonCode").getAsString());
        }
    }

    @Test void activeWaitIsNeverTheLruEvictionVictim() throws Exception {
        try (var first = new AwaitReloadTest.Fixture(); var second = new AwaitReloadTest.Fixture();
             var third = new AwaitReloadTest.Fixture(); var client = new McpServer()) {
            JsonObject cp1 = AwaitReloadTest.data(client, AwaitReloadTest.request(first.agent.server.getLocalPort(), "checkpoint"));
            JsonObject cp2 = AwaitReloadTest.data(client, AwaitReloadTest.request(second.agent.server.getLocalPort(), "checkpoint"));
            JsonObject r = AwaitReloadTest.waitRequest(first.agent.server.getLocalPort(), cp1);
            r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs", "60000");
            RequestCancellation cancel = new RequestCancellation();
            var result = CompletableFuture.supplyAsync(() -> client.handle(r, cancel));
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (first.commands.stream().noneMatch(c -> c.startsWith("RELOAD status")) && System.nanoTime() < until) Thread.sleep(5);
                assertTrue(first.commands.stream().anyMatch(c -> c.startsWith("RELOAD status")));
                AwaitReloadTest.data(client, AwaitReloadTest.request(third.agent.server.getLocalPort(), "checkpoint"));
                assertFalse(result.isDone(), "active first subscription must remain open");
                assertEquals("session_changed", AwaitReloadTest.data(client, AwaitReloadTest.waitRequest(second.agent.server.getLocalPort(), cp2)).get("reasonCode").getAsString());
            } finally { cancel.cancel(); result.get(2, TimeUnit.SECONDS); }
        }
    }

    @Test void malformedAndOversizedBroadcastsInvalidateObservation() throws Exception {
        for (String raw : List.of("{bad", "x".repeat(17000))) {
            try (var f = new AwaitReloadTest.Fixture()) {
                JsonObject cp = f.checkpoint();
                // Rejecting an oversized frame can reset TCP while unread peer bytes remain.
                f.acceptsReset = true; f.output.get().println(raw);
                JsonObject d = AwaitReloadTest.data(f.server, f.waitFor(cp, false, true));
                assertEquals("unavailable", d.get("status").getAsString());
                assertFalse(d.get("reloadConfirmed").getAsBoolean());
            }
        }
    }
    @Test void countAndCharacterEvictionInvalidateOldCursors() throws Exception {
        for (boolean large : List.of(false, true)) {
            try (var f = new AwaitReloadTest.Fixture()) {
                JsonObject cp = f.checkpoint();
                int count = large ? 90 : EventSubscription.MAX_EVENTS + 1;
                String text = large ? "x".repeat(15000) : "bounded event";
                for (int i = 0; i < count; i++) f.emit("INFO", text);
                f.checkpoint(); // same-stream barrier ensures the ring consumed all preceding frames
                JsonObject d = AwaitReloadTest.data(f.server, f.waitFor(cp, false, true));
                assertEquals("cursor_gap", d.get("reasonCode").getAsString(), d.toString());
            }
        }
    }

    @Test void futureForeignAndChangedJvmCursorsNeverStartAReplacementWait() throws Exception {
        for (String mode : List.of("future", "subscription", "jvm")) {
            try (var f = new AwaitReloadTest.Fixture()) {
                JsonObject cp = f.checkpoint(), r = f.waitFor(cp, false, true);
                JsonObject a = r.getAsJsonObject("params").getAsJsonObject("arguments");
                if (mode.equals("future")) a.addProperty("afterCursor", "999999");
                if (mode.equals("subscription")) a.addProperty("subscriptionId", "another-subscription");
                if (mode.equals("jvm")) f.session = "changed-session";
                JsonObject d = AwaitReloadTest.data(f.server, r);
                assertEquals("unavailable", d.get("status").getAsString());
                assertEquals(mode.equals("future") ? "invalid_cursor" : "session_changed", d.get("reasonCode").getAsString());
            }
        }
    }

    @Test void cancellationClosesTheOwnedSocketAndInvalidatesItsSubscription() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            JsonObject r = f.waitFor(cp, false, true);
            r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs", "60000");
            RequestCancellation cancellation = new RequestCancellation();
            CompletableFuture<JsonObject> result = CompletableFuture.supplyAsync(() -> f.server.handle(r, cancellation));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (f.commands.stream().noneMatch(c -> c.startsWith("RELOAD status")) && System.nanoTime() < until) Thread.sleep(5);
            assertTrue(f.commands.stream().anyMatch(c -> c.startsWith("RELOAD status")));
            cancellation.cancel();
            JsonObject d = ReloadVerificationTest.text(result.get(2, TimeUnit.SECONDS).getAsJsonObject("result"));
            assertFalse(d.get("reloadConfirmed").getAsBoolean());
            assertEquals("unavailable", d.get("status").getAsString());
            assertEquals("session_changed", AwaitReloadTest.data(f.server, f.waitFor(cp, false, true)).get("reasonCode").getAsString());
        }
    }

    @Test void serverCloseWakesAnActiveWaitAndNoSecondMcpInstanceSharesItsCursor() throws Exception {
        try (var f = new AwaitReloadTest.Fixture(); var other = new McpServer()) {
            JsonObject cp = f.checkpoint(), r = f.waitFor(cp, false, true);
            assertEquals("session_changed", AwaitReloadTest.data(other, r).get("reasonCode").getAsString());
            r.getAsJsonObject("params").getAsJsonObject("arguments").addProperty("timeoutMs", "60000");
            CompletableFuture<JsonObject> result = CompletableFuture.supplyAsync(() -> f.server.handle(r));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (f.commands.stream().noneMatch(c -> c.startsWith("RELOAD status")) && System.nanoTime() < until) Thread.sleep(5);
            f.server.close();
            JsonObject d = ReloadVerificationTest.text(result.get(2, TimeUnit.SECONDS).getAsJsonObject("result"));
            assertFalse(d.get("reloadConfirmed").getAsBoolean());
        }
    }

    @Test void singleReaderRetainsEventsInterleavedBeforeCommandReplies() throws Exception {
        try (var f = new AwaitReloadTest.Fixture()) {
            JsonObject cp = f.checkpoint();
            f.emit("RELOAD", "Reloaded " + AwaitReloadTest.OWNER + " (1ms)");
            f.duringVerify = out -> {
                f.duringVerify = ignored -> { };
                AwaitReloadTest.event(out, "INFO", "unrelated event while VERIFY waits");
            };
            JsonObject d = AwaitReloadTest.data(f.server, f.waitFor(cp, false, true));
            assertEquals("applied", d.get("status").getAsString());
            JsonObject next = f.checkpoint();
            assertTrue(Long.parseLong(next.get("cursor").getAsString()) >= 2);
        }
    }
}
