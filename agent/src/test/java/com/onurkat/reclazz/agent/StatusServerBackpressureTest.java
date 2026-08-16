/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: a status client that stops reading must never stall the
 * thread emitting events.
 *
 * Before the fix, ClientConnection.send() wrote and flushed synchronously
 * on the caller's thread — which is the reload thread. A client whose
 * receive window filled up (suspended IDE, hung debugger) blocked the
 * whole hot-reload pipeline indefinitely.
 */
class StatusServerBackpressureTest {

    private StatusServer server;
    private Socket client;

    @AfterEach
    void tearDown() {
        try { if (client != null) client.close(); } catch (IOException ignored) {}
        if (server != null) server.stop();
    }

    @Test
    void slowClientDoesNotBlockEventEmitter() throws Exception {
        Path portFile = Files.createTempFile("reclazz-port", ".tmp");
        Files.deleteIfExists(portFile);

        server = new StatusServer(0, portFile);
        server.start();

        int port = Integer.parseInt(Files.readString(portFile).trim());

        // Connect but NEVER read: the socket buffer fills up quickly.
        client = new Socket("127.0.0.1", port);

        // Give the accept loop a moment to register the connection.
        long deadline = System.currentTimeMillis() + 5000;
        while (server.getClientCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, server.getClientCount(), "client should be connected");

        // Emit far more than any socket buffer or queue can hold. With the
        // blocking implementation this call never returns.
        String payload = "x".repeat(2000);
        CountDownLatch done = new CountDownLatch(1);
        Thread emitter = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                StatusReporter.info(payload);
            }
            done.countDown();
        }, "test-emitter");
        emitter.setDaemon(true);
        emitter.start();

        assertTrue(done.await(30, TimeUnit.SECONDS),
                "emitting events must not block on a client that never reads");
    }
}
