/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

/**
 * How a failure is described to the developer.
 *
 * <p>The agent reported failures by appending {@code getMessage()}, which is
 * only sometimes a sentence. Measured on the exceptions this code actually
 * catches, on stock JDK 21:
 *
 * <pre>
 *   walking a directory that is gone   "/app/target/classes"   the path, no reason
 *   reading a file that is gone        "/app/.../Order.class"  the path again
 *   new IllegalStateException()        null
 *   a reflective call that threw       null                    the real cause is inside
 *   a closed WatchService              null
 * </pre>
 *
 * <p>Which is how the log came to contain lines like {@code FileWatcher error:
 * null}, {@code Error in change handler: null} and {@code Failed to scan new
 * directory /app/classes: /app/classes}. The last one says the path twice and
 * the reason nowhere; the middle one is an entire reload failing and telling
 * the developer nothing at all.
 *
 * <p>The type is the missing half. {@code NoSuchFileException} against
 * {@code AccessDeniedException} is the whole answer for the same sentence, and
 * it is there for free. So a failure is named by type, with its message when
 * the message adds something, and with the cause when the throwable that was
 * caught is only a wrapper around the one that matters.
 */
public final class Failures {

    /** Wrappers whose own message is never the interesting one. */
    private static final int MAX_UNWRAP = 4;

    /** Long enough for a real message, short enough to stay a log line. */
    private static final int MAX_LENGTH = 300;

    private Failures() {
    }

    /**
     * @param failure what was caught
     * @return the type, its message when it has one, and the cause when the
     *         cause is the part that explains anything
     */
    public static String describe(Throwable failure) {
        if (failure == null) return "no failure";

        Throwable real = unwrap(failure);
        StringBuilder described = new StringBuilder(name(real));
        String message = trimmed(real.getMessage());
        if (message != null) {
            described.append(": ").append(message);
        }

        Throwable cause = real.getCause();
        if (cause != null && cause != real) {
            described.append(" (caused by ").append(name(cause));
            String causeMessage = trimmed(cause.getMessage());
            if (causeMessage != null) described.append(": ").append(causeMessage);
            described.append(')');
        }

        String result = described.toString();
        return result.length() <= MAX_LENGTH
                ? result
                : result.substring(0, MAX_LENGTH) + "...";
    }

    /**
     * Past the wrappers, to the throwable that says something.
     *
     * <p>A reflective call that fails arrives as an InvocationTargetException
     * whose own message is null, and this agent calls almost everything
     * reflectively. Only wrappers that add nothing are stepped through: one
     * that carries its own message is the one that was thrown on purpose.
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_UNWRAP; depth++) {
            Throwable cause = current.getCause();
            boolean saysNothing = trimmed(current.getMessage()) == null;
            if (cause == null || cause == current || !saysNothing) return current;
            current = cause;
        }
        return current;
    }

    /** The class name a developer would recognise, without the package. */
    private static String name(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        // An anonymous or synthetic class has no simple name to speak of.
        return name.isEmpty() ? failure.getClass().getName() : name;
    }

    private static String trimmed(String message) {
        if (message == null) return null;
        String trimmed = message.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
