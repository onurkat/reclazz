/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SuperclassReportingVerificationTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private int request;

    @Test
    void receiptsAndPendingNeverClaimChangedHierarchyAcrossRepeatedSaves() throws Exception {
        assertTrue(Files.isRegularFile(Path.of(System.getProperty("reclazz.agent.jar"))));
        Path port = dir.resolve("status.port");
        try (var app = WatchedApp.in(dir)
                .agentArgs("startupDelaySec=1,debounceMs=100,portFile=" + port)
                .with("A", "package app; public class A { public String who() { return \"A\"; } }")
                .with("B", "package app; public class B { public String onlyNew() { return \"B-only\"; } }")
                .with("Service", service("A", 1, false, false))
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Service held = new Service();
                                Class<?> originalClass = held.getClass();
                                ClassLoader originalLoader = originalClass.getClassLoader();
                                Class.forName("app.B");
                                held.state = 19;
                                while (true) {
                                    System.out.println("LIVE " + held.body() + "/" + held.c()
                                        + " parent=" + held.getClass().getSuperclass().getSimpleName()
                                        + " same=" + (held.self() == held && held.getClass() == originalClass
                                            && held.getClass().getClassLoader() == originalLoader)
                                        + " state=" + held.state + " who=" + held.who());
                                    Thread.sleep(100);
                                }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            app.awaitOrFail("LIVE v1/c1 parent=A same=true state=19 who=A", "original receiver ready");
            try (var socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(port).trim()))) {
                socket.setSoTimeout(3000);
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                String ordinary = save(app, "A", 2, false, false);
                JsonNode applied = awaitReceipt(socket, input, ordinary, "applied");
                String session = applied.path("sessionId").asText();
                assertFalse(session.isBlank());
                freshBehavior(app, "v2/c2");

                String salvage = save(app, "B", 3, false, false);
                JsonNode partial = awaitReceipt(socket, input, salvage, "unverified");
                assertEquals(session, partial.path("sessionId").asText());
                freshBehavior(app, "v3/c3");
                assertEquals("mismatch", query(socket, input, ordinary).path("status").asText());
                assertTruthfulPartial(partial);
                assertPending(socket, input);

                String pinned = save(app, "B", 4, true, false);
                JsonNode pinReceipt = awaitReceipt(socket, input, pinned, "unverified");
                assertEquals(session, pinReceipt.path("sessionId").asText());
                freshBehavior(app, "v4/c3");
                assertEquals("mismatch", query(socket, input, salvage).path("status").asText());
                assertTruthfulPartial(pinReceipt);
                assertTrue(pending(socket, input).stream().anyMatch(line ->
                        line.contains("c") && line.contains("pinned to the previous implementation")));

                String previous = pinned;
                for (int version : List.of(5, 6)) {
                    String refused = save(app, "B", version, false, true);
                    JsonNode failed = awaitReceipt(socket, input, refused, "failed");
                    assertEquals(session, failed.path("sessionId").asText());
                    assertTrue(failed.path("detail").asText().contains("typed takes or returns B"), failed.toString());
                    freshBehavior(app, "v4/c3");
                    assertEquals("mismatch", query(socket, input, previous).path("status").asText());
                    assertPending(socket, input);
                    previous = refused;
                }

                String recovered = save(app, "A", 7, false, false);
                JsonNode recovery = awaitReceipt(socket, input, recovered, "applied");
                assertEquals(session, recovery.path("sessionId").asText());
                freshBehavior(app, "v7/c7");
                assertEquals("mismatch", query(socket, input, previous).path("status").asText());
                // The session ledger is historical; ordinary recovery is not a restart.
                assertPending(socket, input);
            }
        }
    }

    private static String service(String parent, int version, boolean pinned, boolean blocked) {
        return "package app; public class Service extends " + parent + " {"
                + " public int state = 7; public String body() { return \"v" + version + "\"; }"
                + " public String c() { return " + (pinned ? "onlyNew()" : "\"c" + version + "\"") + "; }"
                + " public Object self() { return this; }"
                + (blocked ? " public B typed() { return null; }" : "") + " }";
    }

    private String save(WatchedApp app, String parent, int version, boolean pinned, boolean blocked) throws Exception {
        app.rewrite("Service", service(parent, version, pinned, blocked));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(app.classesDir().resolve("app/Service.class"))));
    }

    private JsonNode awaitReceipt(Socket socket, BufferedReader in, String hash, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JsonNode result;
        do {
            result = query(socket, in, hash);
            String status = result.path("status").asText();
            if (List.of("applied", "failed", "unverified").contains(status)) {
                assertEquals(expected, status, result.toString());
                assertEquals(hash, result.path("expectedSha256").asText());
                assertEquals(hash, result.path("observedSha256").asText());
                assertFalse(result.path("completedAt").asText().isBlank(), result.toString());
                return result;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No terminal receipt: " + result);
    }

    private JsonNode query(Socket socket, BufferedReader in, String hash) throws Exception {
        String token = "proof" + (++request);
        send(socket, "VERIFY " + token + " app.Service " + hash);
        String prefix = "VERIFY_RESULT " + token + " ";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String message = message(in);
            if (message.startsWith(prefix)) {
                JsonNode result = JSON.readTree(message.substring(prefix.length()));
                assertEquals(token, result.path("requestId").asText());
                assertEquals("app.Service", result.path("className").asText());
                return result;
            }
        }
        throw new AssertionError("Missing " + prefix);
    }

    private static void assertTruthfulPartial(JsonNode receipt) {
        String detail = receipt.path("detail").asText();
        assertTrue(detail.contains("superclass change not applied"), detail);
        assertTrue(detail.contains("restart required"), detail);
        assertFalse(detail.contains("No JVM"), detail);
        assertFalse(detail.contains("were applied"), detail);
    }

    private static void assertPending(Socket socket, BufferedReader in) throws Exception {
        List<String> lines = pending(socket, in);
        assertTrue(lines.stream().anyMatch(line -> line.contains("app.Service")
                && line.contains("superclass change not applied") && line.contains("restart required")), lines.toString());
        assertTrue(lines.stream().noneMatch(line -> line.contains("no JVM") || line.contains("were applied")), lines.toString());
    }

    private static List<String> pending(Socket socket, BufferedReader in) throws Exception {
        send(socket, "PENDING");
        List<String> lines = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String line = message(in);
            lines.add(line);
            if (line.equals("Everything else you changed is already live.")
                    || line.equals("Nothing from this session needs a restart.")) return lines;
        }
        throw new AssertionError("Incomplete PENDING: " + lines);
    }

    private static String message(BufferedReader in) throws Exception {
        String line = in.readLine();
        assertNotNull(line, "status connection closed");
        return JSON.readTree(line).path("message").asText();
    }

    private static void send(Socket socket, String command) throws Exception {
        socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static void freshBehavior(WatchedApp app, String bodies) throws Exception {
        int start = app.output().size();
        String expected = "LIVE " + bodies + " parent=A same=true state=19 who=A";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            List<String> lines = app.output();
            if (lines.subList(start, lines.size()).stream().anyMatch(line -> line.equals(expected))) return;
            Thread.sleep(100);
        }
        fail("No fresh behavior after terminal receipt: " + expected + "\n" + app.tail());
    }
}
