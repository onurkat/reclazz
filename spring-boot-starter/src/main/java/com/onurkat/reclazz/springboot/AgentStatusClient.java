/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.springboot;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the running agent's status over its loopback status socket: connect to
 * {@code 127.0.0.1}, read the {@code CONNECTED} line, send {@code HEALTH}, and
 * collect the {@code INFO} answer. This lives in the starter, not the agent jar,
 * because the agent's own tests forbid the shipped agent from opening a client
 * socket; the only connection this ever makes is to loopback on this machine.
 */
final class AgentStatusClient {

    static final class Status {
        boolean reachable;
        String reason;
        String agent;
        int protocol;
        int port;
        final List<String> lines = new ArrayList<>();
    }

    private static final Pattern AGENT = Pattern.compile("\"agent\":\"([^\"]*)\"");
    private static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");
    private static final Pattern LEVEL = Pattern.compile("\"level\":\"([^\"]*)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\":\"(.*)\",\"timestamp\"");

    private AgentStatusClient() {
    }

    static Status query(ReclazzProperties properties) {
        Status status = new Status();
        Integer port = properties.getPort();
        if (port == null) {
            Path portFile = locatePortFile(properties);
            if (portFile == null) {
                status.reason = "no agent port file found";
                return status;
            }
            try {
                port = Integer.parseInt(Files.readString(portFile).trim());
            } catch (IOException | NumberFormatException e) {
                status.reason = "unreadable port file: " + portFile;
                return status;
            }
        }
        status.port = port;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(500);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            long deadline = System.currentTimeMillis() + 2000;
            boolean asked = false;
            while (System.currentTimeMillis() < deadline) {
                String line;
                try {
                    line = in.readLine();
                } catch (IOException timeout) {
                    if (asked && !status.lines.isEmpty()) {
                        break;
                    }
                    continue;
                }
                if (line == null) {
                    break;
                }
                String level = group(LEVEL, line);
                if ("CONNECTED".equals(level)) {
                    status.reachable = true;
                    status.agent = group(AGENT, line);
                    String v = group(VERSION, line);
                    if (v != null) {
                        status.protocol = Integer.parseInt(v);
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("HEALTH\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    asked = true;
                } else if ("INFO".equals(level)) {
                    String message = group(MESSAGE, line);
                    if (message != null) {
                        status.lines.add(unescape(message));
                    }
                } else if ("HEARTBEAT".equals(level) && asked && !status.lines.isEmpty()) {
                    break;
                }
            }
            if (!status.reachable) {
                status.reason = "connected but no CONNECTED line from the agent";
            }
            return status;
        } catch (IOException e) {
            status.reason = "cannot reach the agent on 127.0.0.1:" + port;
            return status;
        }
    }

    private static Path locatePortFile(ReclazzProperties properties) {
        if (properties.getPortFile() != null) {
            Path p = Paths.get(properties.getPortFile());
            return Files.isRegularFile(p) ? p : null;
        }
        List<Path> candidates = new ArrayList<>();
        // The agent writes where its own portFile argument says; that is the
        // file to read when the application was started with one.
        Path fromAgent = portFileFromAgentFlag(ReclazzAgentDetector.agentFlag());
        if (fromAgent != null) candidates.add(fromAgent);
        if (properties.getHybrisHome() != null) {
            candidates.add(Paths.get(properties.getHybrisHome(), ".reclazz", "agent.port"));
        }
        String base = System.getProperty("user.dir");
        candidates.add(Paths.get(base, ".reclazz", "agent.port"));
        candidates.add(Paths.get(base, ".idea", "reclazz", "agent.port"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** The portFile argument of a -javaagent flag, or null when it names none. */
    static Path portFileFromAgentFlag(String flag) {
        if (flag == null) return null;
        int options = flag.indexOf('=');
        if (options < 0) return null;
        String args = flag.substring(options + 1);
        // SAP's wrapper.conf quotes the whole -javaagent:"jar=args" value.
        if (args.startsWith("\"")) args = args.substring(1);
        if (args.endsWith("\"")) args = args.substring(0, args.length() - 1);
        // Arguments split at a comma only where another key= follows, as the agent splits them.
        for (String argument : args.split(",(?=[A-Za-z][A-Za-z0-9]*=)")) {
            if (argument.startsWith("portFile=") && argument.length() > "portFile=".length()) {
                return Paths.get(argument.substring("portFile=".length()));
            }
        }
        return null;
    }

    private static String group(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }
}
