/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a message out so it can be read.
 *
 * <p>Reclazz explains things, and explanations are sentences: the longest
 * message it prints is 492 characters and ninety of them are over eighty. Every
 * one of those was going out as a single line for the terminal to break
 * wherever it ran out of room, which put the breaks in the middle of words and
 * started every continuation at column zero, where it is indistinguishable from
 * the next entry:
 *
 * <pre>
 *   [Reclazz] [18:25:21] [WARN] This build reads class files up to Java 27, and these are Java
 *   28. Your application runs and method body changes still reload, because the JVM reads them
 *   and that path does not go through the bytecode library. What needs the library is instrume
 *   ntation, so adding or removing members is off until one of two things: compile with --rele
 *   [Reclazz] [18:25:22] [INFO] Reloaded com.acme.Order (34ms)
 * </pre>
 *
 * <p>Breaking on words and indenting the rest under the first line is the whole
 * of it. The paragraph becomes one shape on the screen, and the next entry
 * starts where entries start.
 *
 * <p>A word longer than the room left is left alone rather than cut. Those are
 * class names, file paths and flags, which is to say the parts of the message
 * the developer is going to copy, and a copied identifier with a line break in
 * it is worse than a line that sticks out.
 */
public final class MessageWrap {

    /** Wide enough for prose, narrow enough to stay readable. */
    static final int DEFAULT_WIDTH = 100;

    private static final int MIN_WIDTH = 40;

    private static final int MAX_WIDTH = 160;

    private MessageWrap() {
    }

    /**
     * @param head    the prefix the first line carries, and the rest align under
     * @param message the body, which may contain newlines of its own
     * @param width   the terminal's width
     * @return the lines to print, at least one, never empty
     */
    public static List<String> wrap(String head, String message, int width) {
        List<String> out = new ArrayList<>();
        String indent = " ".repeat(head.length());
        int room = Math.max(MIN_WIDTH / 2, width - head.length());

        String body = message == null ? "" : message;
        boolean first = true;
        for (String paragraph : body.split("\n", -1)) {
            for (String line : fold(paragraph, room)) {
                out.add((first ? head : indent) + line);
                first = false;
            }
        }
        if (out.isEmpty()) out.add(head);
        return out;
    }

    /** One paragraph, broken on spaces, never inside a word. */
    private static List<String> fold(String paragraph, int room) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : paragraph.split(" ")) {
            if (word.isEmpty()) continue;
            if (line.length() == 0) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= room) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0 || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /**
     * The terminal's width, as the terminal reports it.
     *
     * <p>COLUMNS is what a shell exports and what a developer changes by
     * resizing the window. Anything unreadable, absent or absurd falls back to
     * a width that suits prose rather than to whatever the number said.
     */
    public static int terminalWidth() {
        try {
            String columns = System.getenv("COLUMNS");
            if (columns != null) {
                int width = Integer.parseInt(columns.trim());
                if (width >= MIN_WIDTH && width <= MAX_WIDTH) return width;
                if (width > MAX_WIDTH) return MAX_WIDTH;
            }
        } catch (RuntimeException notANumber) {
            // A COLUMNS that does not parse says nothing about the window.
        }
        return DEFAULT_WIDTH;
    }
}
