/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.NoSuchFileException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a failure reads like once it reaches the developer.
 *
 * <p>The agent used to append {@code getMessage()}, which is a sentence only
 * sometimes. Measured on the exceptions this code actually catches: walking a
 * directory that is gone gives the path and no reason, a plain
 * {@code IllegalStateException} gives null, a reflective call that threw gives
 * null with the real cause inside it, and a closed WatchService gives null. So
 * the log contained {@code FileWatcher error: null} and {@code Failed to scan
 * new directory /app/classes: /app/classes}, one of which is an entire reload
 * failing silently and the other of which says the path twice and the reason
 * nowhere.
 */
class FailuresTest {

    @Test
    void anExceptionWithNoMessageIsStillNamed() {
        assertEquals("IllegalStateException", Failures.describe(new IllegalStateException()));
    }

    @Test
    void aMessageIsKeptAndTheTypeIsAdded() {
        String described = Failures.describe(new IOException("disk went away"));

        assertEquals("IOException: disk went away", described);
    }

    /**
     * The type carries the reason here: the message is only the path, which
     * the sentence around it already had. NoSuchFileException against
     * AccessDeniedException is the entire answer.
     */
    @Test
    void aPathOnlyMessageGetsTheTypeThatExplainsIt() {
        String described = Failures.describe(new NoSuchFileException("/app/target/classes"));

        assertTrue(described.startsWith("NoSuchFileException"), described);
        assertTrue(described.contains("/app/target/classes"), described);
    }

    /**
     * Almost everything this agent does is reflective, and a reflective call
     * that fails arrives wrapped in something whose own message is null.
     */
    @Test
    void aWrapperWithNothingToSayIsSteppedThrough() {
        Throwable wrapped = new InvocationTargetException(new IllegalArgumentException("bad key"));

        assertEquals("IllegalArgumentException: bad key", Failures.describe(wrapped));
    }

    @Test
    void severalWrappersAreStillSteppedThrough() {
        Throwable deep = new InvocationTargetException(
                new java.lang.reflect.UndeclaredThrowableException(
                        new IllegalStateException("the real one")));

        assertEquals("IllegalStateException: the real one", Failures.describe(deep));
    }

    /** A wrapper that says something was thrown on purpose and is kept. */
    @Test
    void aWrapperThatSaysSomethingKeepsItsPlace() {
        Throwable meaningful = new IllegalStateException("cannot reload while shutting down",
                new IOException("closed"));

        String described = Failures.describe(meaningful);

        assertTrue(described.startsWith("IllegalStateException: cannot reload while shutting down"),
                described);
        assertTrue(described.contains("caused by IOException: closed"), described);
    }

    @Test
    void aBlankMessageCountsAsNoMessage() {
        assertEquals("IllegalStateException", Failures.describe(new IllegalStateException("   ")));
    }

    @Test
    void nothingCaughtIsSaidRatherThanPrintedAsNull() {
        assertEquals("no failure", Failures.describe(null));
    }

    @Test
    void aVeryLongMessageStaysALogLine() {
        String described = Failures.describe(new IOException("x".repeat(5000)));

        assertTrue(described.length() < 400, "length was " + described.length());
        assertTrue(described.endsWith("..."), described);
    }

    /** A self-referencing cause must not become a loop. */
    @Test
    void aCauseThatPointsAtItselfTerminates() {
        Throwable looping = new IllegalStateException();
        looping.initCause(looping instanceof Throwable ? new IOException() : null);

        assertDoesNotThrow(() -> Failures.describe(looping));
    }
}
