/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import java.nio.file.Path;

final class LauncherDryRun {
    private LauncherDryRun() {}

    static boolean current() {
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            return detected(info.command().orElse(null), info.arguments().orElse(null));
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return false;
        }
    }

    static boolean detected(String command, String[] args) {
        if (command == null || args == null) return false;
        Path name = Path.of(command).getFileName();
        if (name == null) return false;
        switch (name.toString()) {
            case "java", "java.exe", "javaw.exe" -> { }
            default -> { return false; }
        }

        // Stop on the first unrecognized token: it could be the entry point,
        // an argument file, or an option whose operand looks like a launcher flag.
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) return false;
            if (arg.equals("--dry-run")) return true;
            int equals = arg.indexOf('=');
            String option = equals < 0 ? arg : arg.substring(0, equals);
            if (takesOperand(option)) {
                if (equals >= 0) {
                    if (!option.startsWith("--") || equals + 1 == arg.length()) return false;
                } else if (++i == args.length || args[i] == null || args[i].startsWith("@")) {
                    return false;
                }
            } else if (!standalone(arg)) {
                return false;
            }
        }
        return false;
    }

    private static boolean takesOperand(String option) {
        return switch (option) {
            case "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
                    "--add-modules", "--limit-modules", "--add-reads", "--add-exports", "--add-opens",
                    "--patch-module", "--source" -> true;
            default -> false;
        };
    }

    private static boolean standalone(String arg) {
        return switch (arg) {
            case "-ea", "-da", "-esa", "-dsa", "-server", "-client", "--enable-preview" -> true;
            default -> arg.startsWith("-D") || arg.startsWith("-XX:") || arg.startsWith("-javaagent:")
                    || arg.startsWith("-agentlib:") || arg.startsWith("-agentpath:")
                    || arg.startsWith("-Xms") || arg.startsWith("-Xmx");
        };
    }
}
