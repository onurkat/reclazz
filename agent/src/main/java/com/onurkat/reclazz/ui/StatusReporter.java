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
        colorsEnabled = colorsAllowed(System.getenv("TERM"),
                System.getProperty("os.name", ""), System.getenv("NO_COLOR"));
    }

    /**
     * Whether to colour the output at all.
     *
     * <p>Colour here is a second copy of something the line already says: every
     * line carries its level as text, INFO, WARN, ERR and the rest, so a reader
     * who cannot see the colour is not reading a different message. That is
     * what makes turning it off safe, and there is a settled way to ask.
     *
     * <p>NO_COLOR is that way. Its convention is that the variable being
     * present and not empty is the request, whatever the value, and it is what
     * somebody sets when the colours are unreadable against their theme, when
     * they cannot distinguish them, when a screen reader is announcing the
     * escape sequences, or when the output is being captured by something that
     * will keep them forever.
     *
     * @param term    the TERM variable, or null
     * @param osName  the os.name property
     * @param noColor the NO_COLOR variable, or null
     */
    static boolean colorsAllowed(String term, String osName, String noColor) {
        // Asked not to, by the convention that exists for asking.
        if (noColor != null && !noColor.isEmpty()) return false;

        // Disable colors only for explicitly dumb terminals.
        // System.console() is null inside application servers (e.g. Hybris via ant),
        // but stdout may still be a terminal that supports ANSI colors.
        if ("dumb".equals(term)) return false;

        // On Windows without TERM set, disable colors (cmd.exe doesn't support ANSI by default)
        return !(term == null && osName != null
                && osName.toLowerCase(java.util.Locale.ROOT).contains("win"));
    }

    /** Tests set the decision they are testing; nothing in the agent calls this. */
    static void setColorsEnabled(boolean enabled) {
        colorsEnabled = enabled;
    }

    static boolean colorsEnabled() {
        return colorsEnabled;
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
        structuralReload(className, timeMs, null);
    }

    /**
     * @param shape what changed, as "v1, +1 method", or null when the caller
     *              does not have it. It goes in the same parentheses as the
     *              timing: one event that used to print two lines opening with
     *              the same four words now prints one that says both.
     */
    public static void structuralReload(String className, long timeMs, String shape) {
        // A negative timing means the caller did not measure this one, which
        // is the batch path reloading a whole directory under one clock. It
        // used to print "(-1ms)", which reads as a broken measurement rather
        // than an absent one. Reloaded() has always guarded for it; this did not.
        StringBuilder detail = new StringBuilder();
        if (shape != null && !shape.isEmpty()) detail.append(shape);
        if (timeMs >= 0) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(timeMs).append("ms");
        }
        String msg = detail.length() == 0
                ? String.format("Structural reload: %s", className)
                : String.format("Structural reload: %s (%s)", className, detail);
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
        String head = String.format("%s [%s] [%s] ", PREFIX, time, level);

        // Laid out for a terminal, left alone for anything else. Redirected
        // output is a log file somebody greps, and a phrase broken across two
        // lines is a phrase their grep will not find; the window it would have
        // been laid out for is not there to read it either.
        if (!laysOut()) {
            System.out.println(color(colorCode, head + message));
            return;
        }
        for (String line : MessageWrap.wrap(head, message, MessageWrap.terminalWidth())) {
            System.out.println(color(colorCode, line));
        }
    }

    /**
     * Whether to lay a long message out rather than leave it on one line.
     *
     * <p>Auto asks whether standard output is a terminal, and answers no
     * inside an application server even when a person is watching its console,
     * which is why the setting exists. Being wrong towards one line is the
     * cheaper mistake: an unwrapped paragraph is ugly, a wrapped one is
     * unsearchable.
     */
    private static boolean laysOut() {
        if ("always".equals(wrapMode)) return true;
        if ("never".equals(wrapMode)) return false;
        return System.console() != null;
    }

    private static volatile String wrapMode = "auto";

    /** Set from the agent arguments during start-up. */
    public static void setWrapMode(String mode) {
        wrapMode = mode == null ? "auto" : mode;
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
