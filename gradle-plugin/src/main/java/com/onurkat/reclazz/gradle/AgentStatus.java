/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the running Reclazz agent, over its loopback status socket, whether it is
 * attached and how it is doing, and returns one JSON line. This lives in the
 * Gradle plugin, not the agent jar, on purpose: the agent's own tests forbid the
 * shipped agent from ever constructing a client socket, so the status client
 * that has to open one belongs here, next to the build.
 */
final class AgentStatus {

    private static final Pattern AGENT = Pattern.compile("\"agent\":\"([^\"]*)\"");
    private static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");
    private static final Pattern LEVEL = Pattern.compile("\"level\":\"([^\"]*)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\":\"(.*)\",\"timestamp\"");

    private AgentStatus() { }

    /** Options: portFile, port, hybrisHome, timeoutMs, baseDir. Returns a JSON line. */
    static String query(Map<String, String> opts) {
        Integer port = null;
        if (opts.containsKey("port")) {
            try {
                port = Integer.parseInt(opts.get("port").trim());
            } catch (NumberFormatException e) {
                return json(notAttached("invalid port: " + opts.get("port"), null));
            }
        }
        if (port == null) {
            Path portFile = locatePortFile(opts);
            if (portFile == null) {
                return json(notAttached(
                        "no port file found; run the app with the Reclazz agent, or pass --port-file", null));
            }
            try {
                port = Integer.parseInt(Files.readString(portFile).trim());
            } catch (IOException | NumberFormatException e) {
                return json(notAttached("unreadable port file: " + portFile, null));
            }
        }

        int timeoutMs = intOption(opts, "timeoutMs", 2000);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), Math.min(timeoutMs, 2000));
            socket.setSoTimeout(500);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            String agent = null;
            int protocol = 0;
            List<String> health = new ArrayList<>();
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean asked = false;

            while (System.currentTimeMillis() < deadline) {
                String line;
                try {
                    line = in.readLine();
                } catch (IOException timeout) {
                    if (asked && !health.isEmpty()) break;
                    continue;
                }
                if (line == null) break;
                String level = group(LEVEL, line);
                if ("CONNECTED".equals(level)) {
                    agent = group(AGENT, line);
                    String v = group(VERSION, line);
                    if (v != null) protocol = Integer.parseInt(v);
                    OutputStream out = socket.getOutputStream();
                    out.write("HEALTH\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    asked = true;
                } else if ("INFO".equals(level)) {
                    String message = group(MESSAGE, line);
                    if (message != null) health.add(unescape(message));
                } else if ("HEARTBEAT".equals(level) && asked && !health.isEmpty()) {
                    break;
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("attached", true);
            result.put("agent", agent);
            result.put("protocol", protocol);
            result.put("port", port);
            result.put("health", health);
            return json(result);
        } catch (IOException e) {
            return json(notAttached("cannot reach the agent on 127.0.0.1:" + port, port));
        }
    }

    static boolean isAttached(String jsonLine) {
        return jsonLine.contains("\"attached\":true");
    }

    /** The same places the agent writes its port file, in the order it would use them. */
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

    private static Map<String, Object> notAttached(String reason, Integer port) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attached", false);
        result.put("reason", reason);
        if (port != null) result.put("port", port);
        return result;
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

    private static String json(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":").append(jsonValue(e.getValue()));
        }
        return sb.append("}").toString();
    }

    @SuppressWarnings("unchecked")
    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) sb.append(",");
                first = false;
                sb.append(jsonValue(item));
            }
            return sb.append("]").toString();
        }
        return "\"" + escape(value.toString()) + "\"";
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
