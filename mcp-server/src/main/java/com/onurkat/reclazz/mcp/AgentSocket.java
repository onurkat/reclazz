/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the running Reclazz agent over its loopback status socket: connects,
 * reads the {@code CONNECTED} line, optionally sends one command, and collects
 * the {@code INFO} answer lines. This lives outside the agent jar on purpose,
 * because the agent's own tests forbid the shipped agent from opening a client
 * socket.
 */
final class AgentSocket {

    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    static final class Result {
        boolean connected;
        String reason;
        String agent;
        int protocol;
        int port;
        final List<String> lines = new ArrayList<>();
    }

    private AgentSocket() { }

    /** Connect and, if {@code command} is non-null, send it and gather the answer. */
    static Result run(Map<String, String> opts, String command) {
        Result result = new Result();
        if (command != null && (command.length() > 512 || command.codePoints().anyMatch(Character::isISOControl))) {
            result.reason = "invalid agent command";
            return result;
        }
        resolvePort(opts, result);
        if (result.reason != null) return result;
        int port = result.port;

        int timeoutMs = intOption(opts, "timeoutMs", 2000);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), Math.min(timeoutMs, 2000));
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

            while (System.nanoTime() < deadline) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                socket.setSoTimeout((int) Math.max(1, Math.min(500, remainingMs)));
                String line;
                try {
                    line = in.readLine();
                } catch (SocketTimeoutException timeout) {
                    if (!result.lines.isEmpty()) break;
                    continue;
                }
                if (line == null) break;
                JsonObject event;
                try {
                    event = event(line);
                } catch (IOException invalid) {
                    result.reason = result.connected ? "Invalid agent response" : "Invalid agent handshake";
                    return result;
                }
                String level = string(event, "level");
                if (!result.connected) {
                    // Do not send commands to a peer merely claiming a CONNECTED level.
                    // Protocol 1 is the only status-socket contract this client implements.
                    String agent = string(event, "agent");
                    JsonElement version = event.get("version");
                    if (!"CONNECTED".equals(level) || agent == null || agent.isBlank()
                            || version == null || !version.isJsonPrimitive()
                            || !version.getAsJsonPrimitive().isNumber() || !"1".equals(version.getAsString())) {
                        result.reason = "Invalid agent handshake";
                        return result;
                    }
                    result.connected = true;
                    result.agent = agent;
                    result.protocol = 1;
                    if (command == null) return result;
                    OutputStream out = socket.getOutputStream();
                    out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // SCAN has no acknowledgement or result in protocol 1.
                    if ("SCAN".equals(command)) return result;
                } else if ("INFO".equals(level)) {
                    String message = string(event, "message");
                    if (message == null || message.isBlank()) {
                        result.reason = "Invalid agent response";
                        return result;
                    }
                    result.lines.add(message);
                } else if ("HEARTBEAT".equals(level) && !result.lines.isEmpty()) {
                    break;
                }
            }
            if (!result.connected) {
                result.reason = "No agent handshake received before disconnect or timeout";
            } else if (result.lines.isEmpty()) {
                result.reason = "No INFO response received before disconnect or timeout";
            }
            return result;
        } catch (IOException e) {
            result.reason = result.connected ? "Agent connection failed while sending or reading the command"
                    : "cannot reach the agent on 127.0.0.1:" + port;
            return result;
        }
    }

    static void resolvePort(Map<String, String> opts, Result result) {
        Integer port = null;
        if (opts.containsKey("port")) {
            try {
                port = Integer.parseInt(opts.get("port").trim());
            } catch (NumberFormatException e) {
                result.reason = "invalid port: " + opts.get("port");
                return;
            }
        }
        if (port == null) {
            Path portFile = locatePortFile(opts);
            if (portFile == null) {
                result.reason = "no port file found; run the app with the Reclazz agent, or pass port/portFile";
                return;
            }
            try {
                port = Integer.parseInt(Files.readString(portFile).trim());
            } catch (IOException | NumberFormatException e) {
                result.reason = "unreadable port file: " + portFile;
                return;
            }
        }
        result.port = port;
        if (port < 1 || port > 65535) {
            result.reason = "invalid port: " + port;
        }
    }

    private static Path locatePortFile(Map<String, String> opts) {
        if (opts.containsKey("portFile")) {
            Path p = Paths.get(opts.get("portFile"));
            return Files.isRegularFile(p) ? p : null;
        }
        List<Path> candidates = new ArrayList<>();
        if (opts.containsKey("hybrisHome")) {
            candidates.add(Paths.get(opts.get("hybrisHome"), ".reclazz", "agent.port"));
        }
        String base = opts.getOrDefault("baseDir", System.getProperty("user.dir"));
        candidates.add(Paths.get(base, ".reclazz", "agent.port"));
        candidates.add(Paths.get(base, ".idea", "reclazz", "agent.port"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static int intOption(Map<String, String> opts, String key, int fallback) {
        if (!opts.containsKey(key)) return fallback;
        try {
            return Integer.parseInt(opts.get(key).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static JsonObject event(String line) throws IOException {
        try {
            JsonElement value = JSON.fromJson(line, JsonElement.class);
            if (value != null && value.isJsonObject()) return value.getAsJsonObject();
        } catch (JsonParseException invalid) {
            throw new IOException("Invalid agent JSON", invalid);
        }
        throw new IOException("Expected agent JSON object");
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
