/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one socket the agent opens, and what can be done to it.
 *
 * <p>It is bound to loopback and answers two read-only questions, which is what
 * the README promises anyone deciding whether to run this on a shared machine.
 * Probed against a live agent rather than assumed: an unknown command is
 * ignored, so is a blank one, a hundred-kilobyte line is refused and the server
 * is still answering afterwards, and a class name shaped like a path traversal
 * or an absolute path reads nothing and is told there is no class by that name.
 *
 * <p>The part worth a test rather than a probe is the reflection. A DIAGNOSE
 * argument comes back inside the JSON the IDE parses, so an argument carrying
 * quotes could forge a field and put a line of somebody else's choosing into
 * the developer's status view, which is the one thing this surface could be
 * made to do. It cannot, and this is what keeps it that way.
 */
class StatusSocketSurfaceTest {

    private static StatusServer server() {
        return new StatusServer(0, null);
    }

    @Test
    void anUnknownCommandIsIgnored() {
        StatusServer status = server();
        AtomicInteger asked = new AtomicInteger();
        status.setDiagnoser(name -> {
            asked.incrementAndGet();
            return List.of("answered");
        });

        status.handleCommand("SHUTDOWN");
        status.handleCommand("RELOAD com.acme.Order");
        status.handleCommand("");
        status.handleCommand("   ");
        status.handleCommand(null);

        assertEquals(0, asked.get(),
                "the socket answered something that is not one of its two questions");
    }

    /**
     * Refused rather than truncated: half a command is not a command, and the
     * cap is what keeps a client from making the agent hold whatever it felt
     * like sending.
     */
    @Test
    void anOversizedCommandIsRefused() {
        StatusServer status = server();
        AtomicInteger asked = new AtomicInteger();
        status.setDiagnoser(name -> {
            asked.incrementAndGet();
            return List.of("answered");
        });

        status.handleCommand("DIAGNOSE " + "A".repeat(100_000));

        assertEquals(0, asked.get(), "a hundred-kilobyte command was accepted");
    }

    @Test
    void diagnoseReachesTheDiagnoserWithTheNameItWasGiven() {
        StatusServer status = server();
        AtomicReference<String> asked = new AtomicReference<>();
        status.setDiagnoser(name -> {
            asked.set(name);
            return List.of("answered");
        });

        status.handleCommand("DIAGNOSE com.acme.Order");

        assertEquals("com.acme.Order", asked.get());
    }

    /** A name is a name: nothing opens it, so nothing is read by asking. */
    @Test
    void aPathShapedNameIsJustAName() {
        StatusServer status = server();
        AtomicReference<String> asked = new AtomicReference<>();
        status.setDiagnoser(name -> {
            asked.set(name);
            return List.of("no class by that name");
        });

        status.handleCommand("DIAGNOSE ../../../../etc/passwd");

        assertEquals("../../../../etc/passwd", asked.get(),
                "the name is handed on as text and looked up among watched classes");
    }

    /**
     * The reflection that matters. Whatever the argument was, it comes back
     * inside the message and has to stay there, because the other end parses
     * these lines and shows them to the developer as the agent's own words.
     */
    @Test
    void anArgumentCannotForgeAFieldInTheLineTheIdeReads() {
        String hostile = "x\",\"level\":\"ERROR\",\"message\":\"forged";

        String line = "{\"level\":\"INFO\",\"message\":\""
                + StatusServer.escapeJson("Reclazz diagnosis for " + hostile) + "\"}";

        assertTrue(line.contains("\\\"level\\\""),
                () -> "the quotes were not escaped, so the field is forgeable: " + line);
        assertEquals(1, unescapedOccurrences(line, "\"level\":"),
                () -> "a second level field appeared in the line: " + line);
    }

    @Test
    void controlCharactersAndBackslashesSurviveAsText() {
        String escaped = StatusServer.escapeJson("a\nb\tc\\def" + (char) 1);

        assertFalse(escaped.contains("\n"), "a raw newline would end the line early");
        assertFalse(escaped.contains("\t"), "a raw tab is not valid inside a JSON string");
        assertTrue(escaped.contains("\\\\d"), () -> "backslash not escaped: " + escaped);
        assertTrue(escaped.contains("\\u0001"), () -> "control character not escaped: " + escaped);
    }

    /** Occurrences that are not preceded by a backslash, which is the point. */
    private static int unescapedOccurrences(String line, String needle) {
        int count = 0;
        int at = 0;
        while ((at = line.indexOf(needle, at)) >= 0) {
            if (at == 0 || line.charAt(at - 1) != '\\') count++;
            at += needle.length();
        }
        return count;
    }
}
