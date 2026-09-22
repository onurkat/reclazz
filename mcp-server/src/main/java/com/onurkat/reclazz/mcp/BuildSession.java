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
    private final String owner;
    private RequestCancellation cancellation;

    private BuildSession(Socket socket, int timeoutMs, String owner) throws IOException {
        this.socket = socket;
        this.timeoutMs = timeoutMs;
        this.owner = owner;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    static BuildSession open(Map<String, String> opts) throws IOException {
        return open(opts, null, 0);
    }

    static BuildSession open(Map<String, String> opts, RequestCancellation cancellation, long until) throws IOException {
        String owner = opts.getOrDefault("owner", UUID.randomUUID().toString());
        if (!validOwner(owner)) throw new IOException("owner must be 1-64 ASCII letters, digits, _ or -");
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
            if (cancellation != null) cancellation.attach(socket);
            socket.connect(new InetSocketAddress("127.0.0.1", address.port), until == 0 ? timeout : remainingMillis(until));
            BuildSession session = new BuildSession(socket, timeout, owner);
            session.cancellation = cancellation;
            JsonObject hello = session.read(until == 0 ? session.deadline() : until);
            if (!"CONNECTED".equals(text(hello, "level")) || text(hello, "agent").isBlank()
                    || !hello.has("version") || hello.get("version").getAsInt() < 1) {
                throw new IOException("No valid agent handshake");
            }
            return session;
        } catch (IOException | RuntimeException e) {
            socket.close();
            if (cancellation != null) cancellation.detach(socket);
            throw new IOException("Cannot open build session: " + e.getMessage(), e);
        }
    }

    static boolean validOwner(String owner) {
        return owner != null && owner.matches("[A-Za-z0-9_-]{1,64}");
    }

    static boolean validState(String state) {
        return state != null && Set.of("started", "ok", "failed").contains(state);
    }

    void signal(String state) throws IOException {
        if (!validState(state)) throw new IOException("state must be started, ok or failed");
        String token = UUID.randomUUID().toString();
        socket.getOutputStream().write(("BUILD " + state + " request=" + token + " owner=" + owner + "\n")
                .getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        long deadline = deadline();
        try {
            while (true) {
                JsonObject event = read(deadline);
                if (!"INFO".equals(text(event, "level"))) continue;
                String suffix = token + " " + state + " owner=" + owner;
                if (("BUILD_ACK " + suffix).equals(text(event, "message"))) return;
                if (("BUILD_REJECTED " + suffix).equals(text(event, "message"))) {
                    throw new IOException("Build ownership rejected; another hold is active or this owner has not started");
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("BUILD " + state + " was not acknowledged; do not assume build/reload success", e);
        }
    }

    JsonObject doctor() throws IOException {
        String token = UUID.randomUUID().toString();
        socket.getOutputStream().write(("DOCTOR " + token + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        String prefix = "DOCTOR_RESULT " + token + " ";
        long until = deadline();
        try {
            while (true) {
                JsonObject event = read(until);
                if (!"INFO".equals(text(event, "level"))) continue;
                String message = text(event, "message");
                if (!message.startsWith(prefix)) continue;
                return DoctorResult.parse(message.substring(prefix.length()), token);
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("Doctor evidence unavailable: no valid correlated response; check the endpoint and matching agent version", e);
        }
    }

    static boolean validVerification(String name, String hash) {
        return validClassName(name) && hash != null && hash.matches("[0-9a-f]{64}");
    }

    static boolean validClassName(String name) {
        return name != null && name.length() <= 256
                && name.codePoints().noneMatch(Character::isIdentifierIgnorable)
                && name.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*");
    }

    JsonObject verify(String name, String hash) throws IOException {
        return verify(name, hash, deadline());
    }

    JsonObject verify(String name, String hash, long until) throws IOException {
        remainingMillis(until);
        if (!validVerification(name, hash)) throw new IOException("Expected className and lower-case SHA-256");
        String token = UUID.randomUUID().toString();
        socket.getOutputStream().write(("VERIFY " + token + " " + name + " " + hash + "\n")
                .getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        String prefix = "VERIFY_RESULT " + token + " ";

        try {
            while (true) {
                JsonObject event = read(until);
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

    private static int remainingMillis(long until) throws IOException {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) throw new IOException("Verification deadline exceeded");
        return (int) Math.max(1, Math.min(60000, TimeUnit.NANOSECONDS.toMillis(remaining)));
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

    @Override public void close() throws IOException {
        try { socket.close(); }
        finally { if (cancellation != null) cancellation.detach(socket); }
    }
}
