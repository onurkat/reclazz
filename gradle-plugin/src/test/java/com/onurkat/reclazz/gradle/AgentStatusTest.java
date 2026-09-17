/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class AgentStatusTest {

    @TempDir
    Path dir;

    @Test
    void reportsAttachedWithAgentAndHealth() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Path portFile = dir.resolve(".reclazz").resolve("agent.port");
            Files.createDirectories(portFile.getParent());
            Files.writeString(portFile, String.valueOf(server.getLocalPort()));

            Thread responder = new Thread(() -> {
                try (Socket s = server.accept()) {
                    OutputStream out = s.getOutputStream();
                    out.write(("{\"level\":\"CONNECTED\",\"message\":\"hi\",\"timestamp\":\"t\","
                            + "\"version\":1,\"agent\":\"9.9.9\"}\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    if ("HEALTH".equals(in.readLine())) {
                        out.write(("{\"level\":\"INFO\",\"message\":\"Reloads: 3, failures: 0\","
                                + "\"timestamp\":\"t\"}\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    Thread.sleep(400);
                } catch (Exception ignored) {
                    // The client has what it needs; the responder can end.
                }
            });
            responder.setDaemon(true);
            responder.start();

            String json = AgentStatus.query(Map.of(
                    "portFile", portFile.toString(), "timeoutMs", "1500"));

            assertTrue(AgentStatus.isAttached(json), json);
            assertTrue(json.contains("\"agent\":\"9.9.9\""), json);
            assertTrue(json.contains("Reloads: 3, failures: 0"), json);
        }
    }

    @Test
    void reportsNotAttachedWhenNoPortFile() {
        String json = AgentStatus.query(Map.of(
                "portFile", dir.resolve("missing.port").toString()));
        assertFalse(AgentStatus.isAttached(json), json);
        assertTrue(json.contains("\"attached\":false"), json);
    }
}
