/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.onurkat.reclazz.protocol.StatusEndpoint;
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
            result.reason = "invalid Java agent command";
            return result;
        }
        StatusEndpoint endpoint = resolveEndpoint(opts, result);
        if (result.reason != null) return result;
        int port = result.port;

        int timeoutMs = intOption(opts, "timeoutMs", 2000);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), Math.min(timeoutMs, 2000));
            endpoint.authenticate(socket.getOutputStream());
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
                    result.reason = result.connected ? "Invalid Java agent response" : "Invalid Java agent handshake";
                    return result;
                }
                String level = string(event, "level");
                if (!result.connected) {
                    // Do not send commands to a peer merely claiming a CONNECTED level.
                    String agent = string(event, "agent");
                    JsonElement version = event.get("version");
                    if (!"CONNECTED".equals(level) || agent == null || agent.isBlank()
                            || version == null || !version.isJsonPrimitive()
                            || !version.getAsJsonPrimitive().isNumber() || !Integer.toString(endpoint.protocol()).equals(version.getAsString())) {
                        result.reason = "Invalid Java agent handshake";
                        return result;
                    }
                    try { McpTarget.verifyProject(opts, socket, in, deadline); }
                    catch (IOException invalid) { result.reason = invalid.getMessage(); return result; }
                    result.connected = true;
                    result.agent = agent;
                    result.protocol = endpoint.protocol();
                    if (command == null) return result;
                    OutputStream out = socket.getOutputStream();
                    out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // SCAN has no acknowledgement or result in protocol 1.
                    if ("SCAN".equals(command)) return result;
                } else if ("INFO".equals(level)) {
                    String message = string(event, "message");
                    if (message == null || message.isBlank()) {
                        result.reason = "Invalid Java agent response";
                        return result;
                    }
                    result.lines.add(message);
                } else if ("HEARTBEAT".equals(level) && !result.lines.isEmpty()) {
                    break;
                }
            }
            if (!result.connected) {
                result.reason = "No Java agent handshake received; check the private status port file";
            } else if (result.lines.isEmpty()) {
                result.reason = "No INFO response received before disconnect or timeout";
            }
            return result;
        } catch (IOException e) {
            result.reason = result.connected ? "Java agent connection failed while sending or reading the command"
                    : "cannot reach the Java agent on 127.0.0.1:" + port;
            return result;
        }
    }

    static void resolvePort(Map<String, String> opts, Result result) {
        resolveEndpoint(opts, result);
    }

    static StatusEndpoint resolveEndpoint(Map<String, String> opts, Result result) {
        try {
            Integer fixed = opts.containsKey("port") ? Integer.valueOf(opts.get("port").trim()) : null;
            Path file = fixed == null || opts.containsKey("portFile") ? locatePortFile(opts) : null;
            if (file == null && (opts.containsKey("portFile") || fixed == null))
                throw new IOException("no port file found; run the app with the Reclazz Java agent, or pass port/portFile");
            StatusEndpoint endpoint = StatusEndpoint.resolve(file, fixed);
            result.port = endpoint.port();
            return endpoint;
        } catch (NumberFormatException invalid) {
            result.reason = "invalid port";
        } catch (IOException invalid) {
            result.reason = invalid.getMessage();
        }
        return null;
    }

    private static Path locatePortFile(Map<String, String> opts) throws IOException {
        if (opts.containsKey("portFile")) {
            Path p = Paths.get(opts.getOrDefault("baseDir", System.getProperty("user.dir"))).resolve(opts.get("portFile"));
            return Files.isRegularFile(p) ? p : null;
        }
        List<Path> candidates = new ArrayList<>();
        if (opts.containsKey("hybrisHome")) {
            Path p = Paths.get(opts.getOrDefault("baseDir", System.getProperty("user.dir")))
                    .resolve(opts.get("hybrisHome")).resolve(".reclazz/agent.port");
            return Files.isRegularFile(p) ? p : null;
        }
        String base = opts.getOrDefault("baseDir", System.getProperty("user.dir"));
        candidates.add(Paths.get(base, ".reclazz", "agent.port"));
        candidates.add(Paths.get(base, ".idea", "reclazz", "agent.port"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        // Fixed descendant layouts only. Explicit selectors and original defaults
        // above retain precedence, including their fail-closed behavior.
        Path root = Paths.get(base).toRealPath();
        var discovered = new java.util.LinkedHashSet<Path>();
        for (String relative : List.of("", "hybris", "core-customize/hybris", "core-customize", "commerce-suite", "ccv2/hybris")) {
            Path home = root.resolve(relative);
            Path marker = home.resolve("bin/platform/build.xml");
            Path port = home.resolve(".reclazz/agent.port");
            if (!Files.isRegularFile(marker) || !Files.isRegularFile(port)) continue;
            Path realHome = home.toRealPath(), realMarker = marker.toRealPath(), realPort = port.toRealPath();
            if (!realHome.startsWith(root) || !realMarker.startsWith(root) || !realPort.startsWith(root)) continue;
            discovered.add(realPort);
        }
        if (discovered.size() > 1)
            throw new IOException("ambiguous SAP Commerce port files; select --port-file explicitly: " + discovered);
        return discovered.stream().findFirst().orElse(null);
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
            throw new IOException("Invalid Java agent JSON", invalid);
        }
        throw new IOException("Expected Java agent JSON object");
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
