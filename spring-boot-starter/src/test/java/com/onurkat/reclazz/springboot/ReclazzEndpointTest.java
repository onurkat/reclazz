/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReclazzEndpointTest {

    @TempDir
    Path dir;

    @Test
    void reportsNotAttachedWhenNoAgentSocket() {
        ReclazzProperties props = new ReclazzProperties();
        props.setPortFile(dir.resolve("missing.port").toString());
        Map<String, Object> result = new ReclazzEndpoint(props).reclazz();
        // This JVM has no reclazz -javaagent, so attached is false.
        assertThat(result).containsEntry("attached", false);
        assertThat(result).doesNotContainKey("health");
    }

    @Test
    void readsHealthFromTheLoopbackStatusSocket() throws Exception {
        try (ServerSocket agent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Path portFile = dir.resolve("agent.port");
            Files.writeString(portFile, String.valueOf(agent.getLocalPort()));

            Thread responder = new Thread(() -> {
                try (Socket s = agent.accept()) {
                    OutputStream out = s.getOutputStream();
                    out.write(("{\"level\":\"CONNECTED\",\"message\":\"hi\",\"timestamp\":\"t\","
                            + "\"version\":1,\"agent\":\"1.3.0\"}\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    if ("HEALTH".equals(in.readLine())) {
                        out.write(("{\"level\":\"INFO\",\"message\":\"Reloads: 5, failures: 0\","
                                + "\"timestamp\":\"t\"}\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    Thread.sleep(300);
                } catch (Exception ignored) {
                    // The client has what it needs.
                }
            });
            responder.setDaemon(true);
            responder.start();

            ReclazzProperties props = new ReclazzProperties();
            props.setPort(agent.getLocalPort());
            Map<String, Object> result = new ReclazzEndpoint(props).reclazz();

            assertThat(result).containsEntry("agent", "1.3.0");
            assertThat(result).containsEntry("protocol", 1);
            assertThat(result.get("health").toString()).contains("Reloads: 5");
        }
    }
}
