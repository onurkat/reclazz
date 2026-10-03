/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Explicit, instance-local MCP defaults; never searches outside the selected directory. */
final class McpTarget {
    static final String USAGE = "Usage: java -jar reclazz-mcp.jar [--project-dir PATH] [--port-file PATH]";
    private final Path base;
    private final boolean projectSelected;
    private final Path portFile;

    private McpTarget(Path base, boolean projectSelected, Path portFile) {
        this.base = base;
        this.projectSelected = projectSelected;
        this.portFile = portFile;
    }

    static McpTarget parse(String[] args, Path cwd) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!flag.equals("--project-dir") && !flag.equals("--port-file"))
                throw new IllegalArgumentException("Unknown MCP startup option");
            if (flags.containsKey(flag) || i + 1 == args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("Duplicate option or missing value: " + flag);
            flags.put(flag, args[++i]);
        }
        Path base = cwd.toAbsolutePath().normalize();
        boolean selected = flags.containsKey("--project-dir");
        if (selected) base = directory(base, flags.get("--project-dir"));
        Path port = flags.containsKey("--port-file") ? path(base, flags.get("--port-file")) : null;
        return new McpTarget(base, selected, port);
    }

    Map<String, String> options(Map<String, String> call) {
        Map<String, String> opts = new LinkedHashMap<>(call);
        Path effective = call.containsKey("projectDir") ? directory(base, call.get("projectDir")) : base;
        opts.put("baseDir", effective.toString());
        if (projectSelected || call.containsKey("projectDir")) opts.put("projectDir", effective.toString());
        for (String key : new String[]{"portFile", "hybrisHome"})
            if (call.containsKey(key)) opts.put(key, path(effective, call.get(key)).toString());
        if (call.containsKey("port") && call.get("port").isBlank())
            throw new IllegalArgumentException("port must not be blank");
        if (!call.containsKey("port") && !call.containsKey("portFile") && !call.containsKey("hybrisHome")
                && !call.containsKey("projectDir") && portFile != null) opts.put("portFile", portFile.toString());
        return opts;
    }

    private static Path path(Path base, String value) {
        if (value.isBlank() || value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Path must be nonblank, at most 4096 characters and contain no control characters");
        return base.resolve(Path.of(value)).toAbsolutePath().normalize();
    }

    private static Path directory(Path base, String value) {
        try {
            Path p = path(base, value).toRealPath();
            if (!Files.isDirectory(p)) throw new IOException("Not a directory");
            return p;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("projectDir must name an existing directory", invalid);
        }
    }

    /** Check evidence on this socket, not on a separate preflight connection. */
    static void verifyProject(Map<String, String> opts, Socket socket, BufferedReader in, long deadline) throws IOException {
        if (!opts.containsKey("projectDir")) return;
        String token = UUID.randomUUID().toString();
        socket.getOutputStream().write(("DOCTOR " + token + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        String prefix = "DOCTOR_RESULT " + token + " ";
        try {
            while (true) {
                JsonObject event = BuildSession.read(socket, in, deadline);
                if (!event.has("level") || !"INFO".equals(event.get("level").getAsString()) || !event.has("message")) continue;
                String message = event.get("message").getAsString();
                if (!message.startsWith(prefix)) continue;
                JsonObject evidence = DoctorResult.parse(message.substring(prefix.length()), token);
                if (!"observed".equals(evidence.get("status").getAsString())) throw new IOException("No project evidence");
                Path working = Path.of(evidence.get("workingDirectory").getAsString());
                Path project = Path.of(opts.get("projectDir")).toRealPath();
                if (!working.isAbsolute() || !Files.isDirectory(project) || !working.toRealPath().startsWith(project))
                    throw new IOException("Target working directory is outside the selected project");
                return;
            }
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("Selected project could not be confirmed; no requested command sent. Check DOCTOR workingDirectory and projectDir.", invalid);
        }
    }
}
