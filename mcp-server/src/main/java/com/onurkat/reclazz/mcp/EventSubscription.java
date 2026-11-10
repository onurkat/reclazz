/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One reader owns both broadcasts and correlated read-only command responses. */
final class EventSubscription implements AutoCloseable {
    static final int MAX_EVENTS = 2048;
    static final int MAX_CHARS = 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    record Event(long sequence, String level, String message) { }
    static final class Failure extends IOException {
        final String code;
        Failure(String code, String detail) { super(detail); this.code = code; }
    }

    final String id = UUID.randomUUID().toString();
    final Socket socket = new Socket();
    private final Object monitor = new Object();
    private final Object commands = new Object();
    private final Deque<Event> events = new ArrayDeque<>();
    private final BufferedReader input;
    private final Thread reader;
    private long sequence, dropped;
    private int retainedChars;
    private String responsePrefix, response;
    private volatile Failure failure;
    private String sessionId;

    EventSubscription(Map<String, String> opts, int port, RequestCancellation cancel, long until) throws IOException {
        cancel.attach(socket);
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), remaining(until));
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            JsonObject hello = BuildSession.read(socket, input, until);
            if (!"CONNECTED".equals(string(hello, "level")) || string(hello, "agent").isBlank()
                    || !hello.has("version") || !hello.get("version").isJsonPrimitive()
                    || !hello.getAsJsonPrimitive("version").isNumber()
                    || !"1".equals(hello.get("version").getAsString()))
                throw new Failure("unsupported_agent", "No valid protocol-1 handshake");
            McpTarget.verifyProject(opts, socket, input, until);
            socket.setSoTimeout(0);
            reader = new Thread(this::receive, "reclazz-mcp-events");
            reader.setDaemon(true);
            reader.start();
            doctor(opts, until);
        } catch (IOException | RuntimeException invalid) {
            socket.close();
            throw invalid;
        } finally { cancel.detach(socket); }
    }

    String sessionId() { return sessionId; }
    boolean alive() { return failure == null && !socket.isClosed(); }

    JsonObject doctor(Map<String, String> opts, long until) throws IOException {
        String token = UUID.randomUUID().toString();
        JsonObject d = DoctorResult.parse(query("DOCTOR " + token, "DOCTOR_RESULT " + token + " ", until), token);
        if (!"observed".equals(string(d, "status")) || !d.get("verifySupported").getAsBoolean()
                || string(d, "sessionId").isBlank())
            throw new Failure("unsupported_agent", "A session and VERIFY capability are required");
        synchronized (monitor) {
            if (sessionId != null && !sessionId.equals(string(d, "sessionId")))
                throw new Failure("session_changed", "Agent session changed on the observation stream");
            sessionId = string(d, "sessionId");
        }
        if (opts.containsKey("projectDir")) {
            Path cwd = Path.of(string(d, "workingDirectory"));
            if (!cwd.isAbsolute() || !cwd.toRealPath().startsWith(Path.of(opts.get("projectDir")).toRealPath()))
                throw new Failure("target_mismatch", "Working directory is outside the selected project");
        }
        return d;
    }

    JsonObject verify(String owner, String hash, long until) throws IOException {
        String token = UUID.randomUUID().toString();
        String json = query("VERIFY " + token + " " + owner + " " + hash, "VERIFY_RESULT " + token + " ", until);
        JsonObject receipt = BuildSession.verificationResult(json, token, owner, hash);
        for (var field : receipt.entrySet())
            if (!McpServer.isString(field.getValue()) || field.getValue().getAsString().length() > 512)
                throw new Failure("malformed_response", "VERIFY field exceeds observation bounds");
        if (!sessionId.equals(string(receipt, "sessionId")))
            throw new Failure("session_changed", "VERIFY belongs to another agent session");
        return receipt;
    }

    JsonObject state(long until) throws IOException {
        String token = UUID.randomUUID().toString();
        String raw = query("RELOAD status request=" + token, "RELOAD_STATE " + token + " status ", until);
        var m = java.util.regex.Pattern.compile("paused=(true|false) pendingClasses=([0-9]{1,9}) pendingActions=([0-9]{1,9}) buildHold=(none|named|legacy)").matcher(raw);
        if (!m.matches()) throw new Failure("malformed_response", "Invalid admission-state response");
        JsonObject state = new JsonObject();
        state.addProperty("paused", Boolean.parseBoolean(m.group(1)));
        state.addProperty("pendingClasses", Integer.parseInt(m.group(2)));
        state.addProperty("pendingActions", Integer.parseInt(m.group(3)));
        state.addProperty("buildHold", m.group(4));
        return state;
    }

    long cursor() throws IOException { synchronized (monitor) { check(); return sequence; } }

    List<Event> since(long after) throws IOException {
        synchronized (monitor) {
            check();
            if (after < dropped) throw new Failure("cursor_gap", "Events after this cursor were evicted; checkpoint again");
            if (after > sequence) throw new Failure("invalid_cursor", "Cursor is newer than this subscription");
            return events.stream().filter(e -> e.sequence() > after).toList();
        }
    }

    void awaitChange(long previous, long until) throws IOException {
        synchronized (monitor) {
            check();
            if (sequence != previous) return;
            try { monitor.wait(Math.min(100, remaining(until))); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure("cancelled", "Observation interrupted"); }
            check();
        }
    }

    private String query(String command, String prefix, long until) throws IOException {
        synchronized (commands) {
            synchronized (monitor) {
                check(); remaining(until);
                responsePrefix = prefix; response = null;
                try {
                    socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    while (response == null) {
                        check();
                        try { monitor.wait(remaining(until)); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure("cancelled", "Observation interrupted"); }
                    }
                    return response;
                } catch (Failure invalid) {
                    if (invalid.code.equals("deadline"))
                        throw new Failure("response_timeout", "No correlated read-only response before the total deadline");
                    throw invalid;
                } finally { responsePrefix = null; response = null; }
            }
        }
    }

    private void receive() {
        try {
            while (!socket.isClosed()) {
                StringBuilder line = new StringBuilder();
                int c;
                while ((c = input.read()) != -1 && c != '\n') {
                    if (line.length() >= 16384) throw new Failure("malformed_response", "Agent frame exceeds size bound");
                    line.append((char) c);
                }
                if (c == -1) throw new Failure("agent_gone", "Agent event stream disconnected");
                JsonObject frame = JSON.fromJson(line.toString(), JsonObject.class);
                String level = string(frame, "level"), message = string(frame, "message");
                if (!Set.of("INFO", "OK", "WARN", "ERROR", "RELOAD", "STRUCTURAL_RELOAD", "COMPILE", "HEARTBEAT").contains(level))
                    throw new Failure("malformed_response", "Unexpected event level");
                synchronized (monitor) {
                    if (level.equals("INFO") && responsePrefix != null && message.startsWith(responsePrefix)) {
                        if (response != null) throw new Failure("malformed_response", "Duplicate correlated response");
                        response = message.substring(responsePrefix.length());
                    } else if (!level.equals("HEARTBEAT") && !isReceipt(level, message)) {
                        if (sequence == Long.MAX_VALUE) throw new Failure("cursor_overflow", "Observation cursor exhausted");
                        events.addLast(new Event(++sequence, level, message));
                        retainedChars += level.length() + message.length();
                        while (events.size() > MAX_EVENTS || retainedChars > MAX_CHARS) {
                            Event old = events.removeFirst(); dropped = old.sequence();
                            retainedChars -= old.level().length() + old.message().length();
                        }
                    }
                    monitor.notifyAll();
                }
            }
        } catch (IOException | RuntimeException invalid) {
            synchronized (monitor) {
                if (failure == null) failure = invalid instanceof Failure f ? f
                        : new Failure("malformed_response", "Event stream unavailable or malformed");
                monitor.notifyAll();
            }
        } finally { try { socket.close(); } catch (IOException ignored) { } }
    }

    private static boolean isReceipt(String level, String message) {
        return level.equals("INFO") && (message.startsWith("DOCTOR_RESULT ") || message.startsWith("VERIFY_RESULT ")
                || message.startsWith("RELOAD_STATE ") || message.startsWith("BUILD_ACK ") || message.startsWith("BUILD_REJECTED "));
    }
    private void check() throws IOException {
        if (failure != null) throw failure;
        if (socket.isClosed()) throw new Failure("session_changed", "Subscription closed; checkpoint again");
    }
    static int remaining(long until) throws IOException {
        long n = until - System.nanoTime();
        if (n <= 0) throw new Failure("deadline", "Observation deadline exceeded");
        return (int) Math.max(1, Math.min(60000, TimeUnit.NANOSECONDS.toMillis(n)));
    }
    static String string(JsonObject o, String key) throws IOException {
        if (o == null || !McpServer.isString(o.get(key))) throw new Failure("malformed_response", "Missing string field: " + key);
        return o.get(key).getAsString();
    }
    @Override public void close() {
        synchronized (monitor) {
            if (failure == null) failure = new Failure("session_changed", "Subscription closed; checkpoint again");
            try { socket.close(); } catch (IOException ignored) { }
            monitor.notifyAll();
        }
        if (Thread.currentThread() != reader) try { reader.join(1000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
