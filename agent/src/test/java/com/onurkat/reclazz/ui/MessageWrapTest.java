/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a paragraph is laid out, and when it is left alone.
 *
 * <p>Reclazz explains things, so its messages are sentences rather than log
 * lines: the longest is 492 characters and ninety of them are over eighty. Left
 * to the terminal they broke mid-word and every continuation started at column
 * zero, where it reads as the beginning of the next entry rather than the
 * middle of this one.
 */
class MessageWrapTest {

    private static final String HEAD = "[Reclazz] [18:25:21] [WARN] ";

    @org.junit.jupiter.api.AfterEach
    void backToAuto() {
        StatusReporter.setWrapMode("auto");
    }

    @Test
    void breaksOnWordsAndNeverInsideOne() {
        String message = "the quick brown fox jumps over the lazy dog and keeps running";
        List<String> lines = MessageWrap.wrap(HEAD, message, 60);

        assertTrue(lines.size() > 1, "this does not fit on one line at 60 columns");
        String rejoined = String.join(" ", lines).replaceAll("\\s+", " ").trim();
        assertEquals((HEAD + message).replaceAll("\\s+", " ").trim(), rejoined,
                "every word survives, whole and in order");
    }

    @Test
    void continuationLinesAlignUnderTheMessage() {
        List<String> lines = MessageWrap.wrap(HEAD,
                "one two three four five six seven eight nine ten eleven twelve", 60);

        assertTrue(lines.get(0).startsWith(HEAD));
        for (String line : lines.subList(1, lines.size())) {
            assertTrue(line.startsWith(" ".repeat(HEAD.length())),
                    () -> "a continuation starting at column zero reads as a new entry: " + line);
            assertFalse(line.substring(HEAD.length()).startsWith(" "),
                    () -> "indented, but not twice: " + line);
        }
    }

    @Test
    void staysInsideTheWidthItWasGiven() {
        List<String> lines = MessageWrap.wrap(HEAD,
                "one two three four five six seven eight nine ten eleven twelve thirteen", 70);

        for (String line : lines) {
            assertTrue(line.length() <= 70, () -> line.length() + " columns: " + line);
        }
    }

    /**
     * A word longer than the room is what the developer is going to copy: a
     * class name, a path, a flag. It sticks out rather than being cut.
     */
    @Test
    void aWordLongerThanTheRoomIsLeftWhole() {
        String identifier = "com.acme.verylongpackage.that.keeps.going.OrderProcessingServiceImpl";
        List<String> lines = MessageWrap.wrap(HEAD, "Transform failed for " + identifier, 60);

        assertTrue(lines.stream().anyMatch(l -> l.contains(identifier)),
                () -> "the identifier was broken across lines: " + lines);
    }

    @Test
    void aMessageThatFitsStaysOneLine() {
        assertEquals(List.of(HEAD + "Reloaded com.acme.Order (34ms)"),
                MessageWrap.wrap(HEAD, "Reloaded com.acme.Order (34ms)", 100));
    }

    @Test
    void newlinesInTheMessageStayNewlines() {
        List<String> lines = MessageWrap.wrap(HEAD, "first line\nsecond line", 100);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("first line"));
        assertTrue(lines.get(1).endsWith("second line"));
        assertTrue(lines.get(1).startsWith(" ".repeat(HEAD.length())));
    }

    @Test
    void anEmptyMessageStillProducesTheHead() {
        assertEquals(List.of(HEAD), MessageWrap.wrap(HEAD, "", 100));
        assertEquals(List.of(HEAD), MessageWrap.wrap(HEAD, null, 100));
    }

    @Test
    void theWidthIsAlwaysSomethingReadable() {
        int width = MessageWrap.terminalWidth();

        assertTrue(width >= 40 && width <= 160,
                "whatever COLUMNS said, prose needs a sane width, saw " + width);
    }

    /**
     * Redirected output is a log file somebody greps, and a phrase broken over
     * two lines is a phrase their grep will not find. There is also no window
     * to lay it out for. So this is the shape the whole test suite and the
     * integration harness see, and it has to stay one line.
     */
    @Test
    void outputThatIsNotATerminalIsLeftOnOneLine() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.console() == null,
                "this is about the redirected case and these tests are on a terminal");

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            StatusReporter.warn("a message long enough that any terminal would have to break it "
                    + "somewhere, going on well past a hundred columns so that the question is "
                    + "actually being asked rather than answered by the message being short");
        } finally {
            System.setOut(original);
        }

        String printed = captured.toString(StandardCharsets.UTF_8).trim();
        assertFalse(printed.isEmpty(), "nothing was printed");
        assertEquals(1, printed.split("\n").length,
                () -> "redirected output was wrapped, which breaks grep: " + printed);
    }

    /**
     * Auto cannot tell inside an application server, where standard output is
     * not a terminal and a person is reading the console anyway. That is what
     * the setting is for, and it has to work in both directions.
     */
    @Test
    void theSettingOverridesWhatAutoWouldHaveDecided() {
        StatusReporter.setWrapMode("always");
        assertTrue(printed("a message long enough that it has to be broken somewhere, going well "
                + "past a hundred columns so the question is actually asked").split("\n").length > 1,
                "always means always, terminal or not");

        StatusReporter.setWrapMode("never");
        assertEquals(1, printed("a message long enough that it has to be broken somewhere, going "
                + "well past a hundred columns so the question is actually asked").split("\n").length,
                "never means never, terminal or not");
    }

    private static String printed(String message) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            StatusReporter.warn(message);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8).trim();
    }
}
