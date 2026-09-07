/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whatever escapes {@code premain} ends the JVM before the application's
 * main method runs. The agent's own start-up dying of an Error, a missing
 * class or a static initialiser, must cost hot reload and nothing else.
 */
class AgentStartGuardTest {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final StatusReporter.StatusListener listener = (level, message) -> lines.add(level + " " + message);

    @AfterEach
    void reset() {
        ReclazzAgent.startProbe = null;
        StatusReporter.removeListener(listener);
    }

    @Test
    void anErrorDuringStartUpDoesNotEscapePremain() {
        StatusReporter.addListener(listener);
        ReclazzAgent.startProbe = () -> {
            throw new NoClassDefFoundError("com/example/MissingFromTheJar");
        };

        assertDoesNotThrow(() -> ReclazzAgent.premain("", null),
                "an Error out of premain is the JVM refusing to start the application");

        // Another test in this JVM may have started the agent already, in which
        // case the guarded start is never entered and there is nothing to see.
        Assumptions.assumeFalse(lines.stream().anyMatch(l -> l.contains("already initialised")),
                "the agent was started earlier in this JVM");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("ERROR")
                        && l.contains("Failed to initialize Reclazz")
                        && l.contains("MissingFromTheJar")),
                "the failure is said, with its cause: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("starts without hot reload")),
                "and what it means for the application: " + lines);
    }
}
