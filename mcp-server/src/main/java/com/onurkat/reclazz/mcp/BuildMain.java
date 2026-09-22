/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Terminal-only entry point in the MCP jar: wraps a compiler without using a shell. */
public final class BuildMain {
    private BuildMain() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        int index = 0;
        while (index < args.length && !"--".equals(args[index])) {
            String key = switch (args[index++]) {
                case "--port" -> "port";
                case "--port-file" -> "portFile";
                case "--timeout-ms" -> "timeoutMs";
                case "--owner" -> "owner";
                default -> null;
            };
            if (key == null || index == args.length) return usage();
            opts.put(key, args[index++]);
        }
        if (index >= args.length - 1) return usage();
        return execute(opts, Arrays.asList(args).subList(index + 1, args.length));
    }

    static int execute(Map<String, String> opts, List<String> command) {
        Map<String, String> owned = new LinkedHashMap<>(opts);
        owned.putIfAbsent("owner", java.util.UUID.randomUUID().toString());
        if (!BuildSession.validOwner(owned.get("owner"))) return usage();
        System.err.println("Reclazz build owner: " + owned.get("owner")
                + "; retain for recovery with --owner if this build fails or disconnects.");
        try (BuildSession session = BuildSession.open(owned)) {
            session.signal("started"); // Receipt is mandatory BEFORE the child can write any output.
            Process child = null;
            try {
                child = new ProcessBuilder(command).inheritIO().start();
                int exit = child.waitFor();
                session.signal(exit == 0 ? "ok" : "failed");
                return exit;
            } catch (IOException | InterruptedException e) {
                if (child != null && child.isAlive()) child.destroyForcibly();
                try { session.signal("failed"); }
                catch (IOException failed) { e.addSuppressed(failed); }
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("Reclazz build did not complete safely: " + e.getMessage());
                return 1;
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Reclazz build refused: " + e.getMessage());
            return 1;
        }
    }

    private static int usage() {
        System.err.println("Usage: java -cp reclazz-mcp.jar com.onurkat.reclazz.mcp.BuildMain "
                + "[--port N | --port-file PATH] [--timeout-ms N] [--owner TOKEN] -- COMMAND [ARG...]");
        return 2;
    }
}
