/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A module left on a newer toolchain than the server runs compiles cleanly and
 * is only rejected later, by the JVM, in the middle of the redefinition.
 *
 * Measured on a Spring Boot application: bytecode built for Java 21 handed to a
 * Java 17 server produced "Constructor-body refresh skipped:
 * UnsupportedClassVersionError" followed by "Structural reload" and "Spring
 * bean refreshed". The endpoint kept returning the old value. A reload that
 * reports success while the application never changes is the one failure this
 * tool must not have, because the developer stops looking.
 */
class BytecodeVersionTest {

    private static byte[] classFile(int major) {
        byte[] b = new byte[10];
        b[0] = (byte) 0xCA; b[1] = (byte) 0xFE; b[2] = (byte) 0xBA; b[3] = (byte) 0xBE;
        b[6] = (byte) ((major >> 8) & 0xFF);
        b[7] = (byte) (major & 0xFF);
        return b;
    }

    @Test
    void bytecodeNewerThanTheJvmIsRefusedWithBothVersionsNamed() {
        int tooNew = BytecodeVersion.maxSupportedMajor() + 1;

        String reason = BytecodeVersion.rejectionReason(classFile(tooNew));

        assertNotNull(reason, "this JVM cannot load it, so the reload cannot be reported as done");
        assertTrue(reason.contains("Nothing was applied"), reason);
        assertTrue(reason.contains(String.valueOf(BytecodeVersion.javaReleaseOf(tooNew))), reason);
        assertTrue(reason.contains(String.valueOf(Runtime.version().feature())),
                "the developer has to know which end to change. Was: " + reason);
    }

    @Test
    void bytecodeThisJvmCanLoadIsLeftAlone() {
        assertNull(BytecodeVersion.rejectionReason(classFile(BytecodeVersion.maxSupportedMajor())));
        assertNull(BytecodeVersion.rejectionReason(classFile(52)), "Java 8 bytecode still loads");
    }

    /**
     * Half-written files are the normal case on a watched directory, and they
     * are somebody else's problem: this check must not turn them into a
     * version complaint.
     */
    @Test
    void thingsThatAreNotClassFilesAreNotJudgedHere() {
        assertNull(BytecodeVersion.rejectionReason(new byte[] {1, 2, 3}));
        assertNull(BytecodeVersion.rejectionReason(new byte[0]));
        assertNull(BytecodeVersion.rejectionReason(null));
        assertEquals(-1, BytecodeVersion.majorOf("not a class file at all".getBytes()));
    }

    @Test
    void theJvmsOwnLimitIsDerivedFromItsVersion() {
        assertEquals(Runtime.version().feature() + 44, BytecodeVersion.maxSupportedMajor());
        assertEquals(Runtime.version().feature(), BytecodeVersion.javaReleaseOf(BytecodeVersion.maxSupportedMajor()));
    }
}
