/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Formatted console output for Reclazz.
 * Uses ANSI colors for terminal output and notifies StatusListeners.
 */
public class StatusReporter {

    /**
     * Listener interface for receiving structured status events.
     * Used by StatusServer to broadcast events to the IntelliJ plugin.
     */
    public interface StatusListener {
        void onEvent(String level, String message);
    }

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    private static final String PREFIX = "[Reclazz]";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static boolean colorsEnabled = true;
    private static final List<StatusListener> listeners = new CopyOnWriteArrayList<>();

    static {
        // Disable colors only for explicitly dumb terminals.
        // System.console() is null inside application servers (e.g. Hybris via ant),
        // but stdout may still be a terminal that supports ANSI colors.
        String term = System.getenv("TERM");
        if ("dumb".equals(term)) {
            colorsEnabled = false;
        }
        // On Windows without TERM set, disable colors (cmd.exe doesn't support ANSI by default)
        if (term == null && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            colorsEnabled = false;
        }
    }

    public static void addListener(StatusListener listener) {
        listeners.add(listener);
    }

    public static void removeListener(StatusListener listener) {
        listeners.remove(listener);
    }

    public static void banner() {
        String version = StatusReporter.class.getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        String banner = String.format("""

                ══════════════════════════════════════════════
                  Reclazz v%s
                  Hot-reload for Java applications
                  www.onurkat.com/reclazz
                ══════════════════════════════════════════════
                """, version);
        System.out.println(color(CYAN + BOLD, banner));
    }

    public static void info(String message) {
        log(BLUE, "INFO", message);
        notifyListeners("INFO", message);
    }

    public static void success(String message) {
        log(GREEN, " OK ", message);
        notifyListeners("OK", message);
    }

    public static void warn(String message) {
        log(YELLOW, "WARN", message);
        notifyListeners("WARN", message);
    }

    public static void error(String message) {
        log(RED, " ERR", message);
        notifyListeners("ERROR", message);
    }

    public static void reload(String className, long timeMs) {
        String msg = timeMs >= 0
                ? String.format("Reloaded %s (%dms)", className, timeMs)
                : String.format("Reloaded %s", className);
        log(GREEN + BOLD, "SWAP", msg);
        notifyListeners("RELOAD", msg);
    }

    public static void structuralReload(String className, long timeMs) {
        String msg = String.format("Structural reload: %s (%dms)", className, timeMs);
        log(GREEN + BOLD, "STRC", msg);
        notifyListeners("STRUCTURAL_RELOAD", msg);
    }

    public static void compile(String fileName, long timeMs) {
        String msg = String.format("Compiled %s (%dms)", fileName, timeMs);
        log(CYAN, "COMP", msg);
        notifyListeners("COMPILE", msg);
    }

    private static void log(String colorCode, String level, String message) {
        String time = LocalTime.now().format(TIME_FMT);
        String formatted = String.format("%s [%s] [%s] %s", PREFIX, time, level, message);
        System.out.println(color(colorCode, formatted));
    }

    private static String color(String code, String text) {
        if (colorsEnabled) {
            return code + text + RESET;
        }
        return text;
    }

    private static void notifyListeners(String level, String message) {
        for (StatusListener listener : listeners) {
            try {
                listener.onEvent(level, message);
            } catch (Exception ignored) {
                // Don't let listener errors break the agent
            }
        }
    }
}
