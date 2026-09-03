/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the output without the colour.
 *
 * <p>Colour is the fastest way to say which lines matter and the easiest one to
 * be unable to use. People turn it off because it is unreadable against their
 * terminal theme, because they cannot distinguish the hues, because a screen
 * reader announces the escape sequences as text, or because the output is being
 * captured by something that will keep them forever.
 *
 * <p>Two things follow, and both are checked here. Turning it off has to be
 * possible in the way people already know to ask, which is NO_COLOR: present
 * and not empty means no colour, whatever the value. And turning it off must
 * not remove anything, which holds because every line carries its level as
 * text, so the colour was always a second copy of something already said.
 */
class ColourIsNeverTheOnlySignalTest {

    @AfterEach
    void restoreColours() {
        StatusReporter.setColorsEnabled(
                StatusReporter.colorsAllowed(System.getenv("TERM"),
                        System.getProperty("os.name", ""), System.getenv("NO_COLOR")));
    }

    @Test
    void noColourIsHonouredWhateverItSays() {
        assertFalse(StatusReporter.colorsAllowed("xterm-256color", "Mac OS X", "1"));
        assertFalse(StatusReporter.colorsAllowed("xterm-256color", "Mac OS X", "0"),
                "the convention is that the variable being set is the request, not its value");
        assertFalse(StatusReporter.colorsAllowed("xterm-256color", "Mac OS X", "anything"));
    }

    @Test
    void anAbsentOrEmptyNoColourAsksForNothing() {
        assertTrue(StatusReporter.colorsAllowed("xterm-256color", "Mac OS X", null));
        assertTrue(StatusReporter.colorsAllowed("xterm-256color", "Mac OS X", ""),
                "an empty value is not the variable being set");
    }

    @Test
    void theOlderReasonsToGoWithoutColourStillApply() {
        assertFalse(StatusReporter.colorsAllowed("dumb", "Linux", null));
        assertFalse(StatusReporter.colorsAllowed(null, "Windows 11", null));
        assertTrue(StatusReporter.colorsAllowed("xterm", "Windows 11", null));
    }

    /**
     * The guarantee that makes turning colour off safe. Every level has to be
     * legible as text, because for the reader who cannot see the colour that
     * text is the whole of the signal.
     */
    @Test
    void everyLevelSaysWhatItIsInWords() {
        StatusReporter.setColorsEnabled(false);

        assertTrue(printed(() -> StatusReporter.info("something")).contains("[INFO]"));
        assertTrue(printed(() -> StatusReporter.warn("something")).contains("[WARN]"));
        assertTrue(printed(() -> StatusReporter.error("something")).contains("[ ERR]"));
        assertTrue(printed(() -> StatusReporter.success("something")).contains("[ OK ]"));
    }

    /** The escape character itself, which is what a screen reader reads out. */
    private static final char ESCAPE = 0x1B;

    @Test
    void withColourOffNothingAnsiIsLeftInTheLine() {
        StatusReporter.setColorsEnabled(false);

        String line = printed(() -> StatusReporter.warn("a warning"));

        assertEquals(-1, line.indexOf(ESCAPE),
                () -> "an escape sequence survived: "
                        + line.replace(String.valueOf(ESCAPE), "<ESC>"));
        assertTrue(line.contains("a warning"), "and the message itself is still there");
    }

    /** And with it on, the level is still spelled out beside the colour. */
    @Test
    void withColourOnTheWordIsStillThere() {
        StatusReporter.setColorsEnabled(true);

        String line = printed(() -> StatusReporter.warn("a warning"));

        assertTrue(line.contains("[WARN]"),
                () -> "the colour is the only thing saying this is a warning: " + line);
    }

    private static String printed(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
