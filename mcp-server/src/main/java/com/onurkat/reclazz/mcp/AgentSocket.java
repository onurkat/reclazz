/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the running Reclazz agent over its loopback status socket: connects,
 * reads the {@code CONNECTED} line, optionally sends one command, and collects
 * the {@code INFO} answer lines. This lives outside the agent jar on purpose,
 * because the agent's own tests forbid the shipped agent from opening a client
 * socket.
 */
final class AgentSocket {

    private static final Pattern AGENT = Pattern.compile("\"agent\":\"([^\"]*)\"");
    private static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");
    private static final Pattern LEVEL = Pattern.compile("\"level\":\"([^\"]*)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\":\"(.*)\",\"timestamp\"");

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
            socket.setSoTimeout(500);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean asked = command == null;

            while (System.currentTimeMillis() < deadline) {
                String line;
                try {
                    line = in.readLine();
                } catch (IOException timeout) {
                    if (asked && !result.lines.isEmpty()) break;
                    if (asked && command == null) break;
                    continue;
                }
                if (line == null) break;
                String level = group(LEVEL, line);
                if ("CONNECTED".equals(level)) {
                    result.connected = true;
                    result.agent = group(AGENT, line);
                    String v = group(VERSION, line);
                    if (v != null) result.protocol = Integer.parseInt(v);
                    if (command != null) {
                        OutputStream out = socket.getOutputStream();
                        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        asked = true;
                    }
                } else if ("INFO".equals(level)) {
                    String message = group(MESSAGE, line);
                    if (message != null) result.lines.add(unescape(message));
                } else if ("HEARTBEAT".equals(level) && asked && !result.lines.isEmpty()) {
                    break;
                }
            }
            if (!result.connected) {
                result.reason = "connected but no CONNECTED line from the agent";
            }
            return result;
        } catch (IOException e) {
            result.reason = "cannot reach the agent on 127.0.0.1:" + port;
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

    private static String group(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }
}
