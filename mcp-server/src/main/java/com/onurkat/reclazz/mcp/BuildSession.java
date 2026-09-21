/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A receipt barrier on one connection; never reconnect to a different agent mid-build. */
final class BuildSession implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader in;
    private final int timeoutMs;

    private BuildSession(Socket socket, int timeoutMs) throws IOException {
        this.socket = socket;
        this.timeoutMs = timeoutMs;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    static BuildSession open(Map<String, String> opts) throws IOException {
        AgentSocket.Result address = new AgentSocket.Result();
        AgentSocket.resolvePort(opts, address);
        if (address.reason != null) throw new IOException(address.reason);
        int timeout = 5000;
        if (opts.containsKey("timeoutMs")) {
            try { timeout = Integer.parseInt(opts.get("timeoutMs")); }
            catch (NumberFormatException e) { throw new IOException("Invalid timeoutMs", e); }
        }
        if (timeout < 1 || timeout > 60000) throw new IOException("timeoutMs must be between 1 and 60000");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", address.port), timeout);
            BuildSession session = new BuildSession(socket, timeout);
            JsonObject hello = session.read(session.deadline());
            if (!"CONNECTED".equals(text(hello, "level")) || text(hello, "agent").isBlank()
                    || !hello.has("version") || hello.get("version").getAsInt() < 1) {
                throw new IOException("No valid agent handshake");
            }
            return session;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw new IOException("Cannot open build session: " + e.getMessage(), e);
        }
    }

    static boolean validState(String state) {
        return state != null && Set.of("started", "ok", "failed").contains(state);
    }

    void signal(String state) throws IOException {
        if (!validState(state)) throw new IOException("state must be started, ok or failed");
        String token = UUID.randomUUID().toString();
        socket.getOutputStream().write(("BUILD " + state + " request=" + token + "\n")
                .getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        long deadline = deadline();
        try {
            while (true) {
                JsonObject event = read(deadline);
                if ("INFO".equals(text(event, "level"))
                        && ("BUILD_ACK " + token + " " + state).equals(text(event, "message"))) return;
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("BUILD " + state + " was not acknowledged; do not assume build/reload success", e);
        }
    }

    static boolean validVerification(String name, String hash) {
        return name != null && name.length() <= 256
                && name.codePoints().noneMatch(Character::isIdentifierIgnorable)
                && name.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*")
                && hash != null && hash.matches("[0-9a-f]{64}");
    }

    JsonObject verify(String name, String hash) throws IOException {
        if (!validVerification(name, hash)) throw new IOException("Expected className and lower-case SHA-256");
        String token = UUID.randomUUID().toString();
        socket.getOutputStream().write(("VERIFY " + token + " " + name + " " + hash + "\n")
                .getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        String prefix = "VERIFY_RESULT " + token + " ";
        long deadline = deadline();
        try {
            while (true) {
                JsonObject event = read(deadline);
                String message = text(event, "message");
                if (!"INFO".equals(text(event, "level")) || !message.startsWith(prefix)) continue;
                JsonObject result = JsonParser.parseString(message.substring(prefix.length())).getAsJsonObject();
                for (String field : Set.of("requestId", "sessionId", "className", "expectedSha256",
                        "observedSha256", "status", "source", "detail", "completedAt")) {
                    if (!result.has(field) || !result.get(field).isJsonPrimitive()
                            || !result.getAsJsonPrimitive(field).isString()) throw new IOException("Invalid VERIFY field: " + field);
                }
                String status = text(result, "status");
                if (!token.equals(text(result, "requestId")) || !name.equals(text(result, "className"))
                        || !hash.equals(text(result, "expectedSha256")) || text(result, "sessionId").isBlank()
                        || !Set.of("applied", "failed", "unverified", "running", "mismatch", "not_observed").contains(status)) {
                    throw new IOException("Uncorrelated VERIFY result");
                }
                if (status.equals("applied") && (!hash.equals(text(result, "observedSha256"))
                        || text(result, "completedAt").isBlank())) throw new IOException("Incomplete applied receipt");
                return result;
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("Reload not verified: no valid correlated receipt", e);
        }
    }

    private long deadline() { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs); }

    private JsonObject read(long deadline) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() < 16384) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Agent acknowledgement timed out");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int c = in.read();
            if (c < 0) throw new IOException("Agent disconnected");
            if (c == '\n') {
                try { return JsonParser.parseString(line.toString()).getAsJsonObject(); }
                catch (RuntimeException e) { throw new IOException("Invalid agent response", e); }
            }
            line.append((char) c);
        }
        throw new IOException("Agent response exceeds size limit");
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    @Override public void close() throws IOException { socket.close(); }
}
