/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LauncherDryRunTest {
    private static boolean detects(String... args) {
        return LauncherDryRun.detected("java", args);
    }

    @Test
    void acceptsOnlyAnExactOptionInTheLauncherPrefix() {
        assertTrue(detects("--dry-run", "Main"));
        assertTrue(detects("-javaagent:path with spaces.jar", "-Dk=--dry-run", "-cp", "two roots", "--dry-run", "Main"));
        assertTrue(detects("--module-path=library", "--dry-run", "-m", "mod/Main"));
        assertFalse(detects("-Dk=--dry-run", "Main"));
        assertFalse(detects("--dry-run=true", "Main"));
        assertFalse(detects("--class-path", "--dry-run", "Main"));
        assertFalse(detects("--module-path=--dry-run", "Main"));
    }

    @Test
    void stopsAtEveryEntryPointAndAmbiguousPrefix() {
        for (String boundary : new String[]{"Main", "Main.java", "-jar", "-m", "--module",
                "--module=mod/Main", "@args", "--", "--unknown"}) {
            assertFalse(detects(boundary, "--dry-run"), boundary);
        }
        assertFalse(detects("-cp", "@file", "--dry-run"));
        assertFalse(detects("-cp"));
    }

    @Test
    void unavailableOrUnrecognizedLaunchersStartNormally() {
        assertFalse(LauncherDryRun.detected(null, new String[]{"--dry-run"}));
        assertFalse(LauncherDryRun.detected("java", null));
        assertFalse(LauncherDryRun.detected("embedded", new String[]{"--dry-run"}));
        assertFalse(detects());
    }
}
