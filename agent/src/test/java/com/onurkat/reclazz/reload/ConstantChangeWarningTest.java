/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one thing about a changed compile-time constant no reload can fix,
 * said instead of left to be discovered.
 *
 * <p>javac copies a ConstantValue into every use site, so after a reload the
 * classes compiled against the old value keep behaving as if nothing changed,
 * because their own bytecode holds the old number. The warning is the
 * feature; these tests hold what it fires on and what it stays quiet about.
 */
class ConstantChangeWarningTest {

    @Test
    void aChangedConstantIsNamedWithBothValues() {
        byte[] before = classWithConstants("MAX", 3, "NAME", "reclazz");
        byte[] after = classWithConstants("MAX", 5, "NAME", "reclazz");

        List<String> changed = ConstantChangeWarning.changedConstants(before, after);
        assertEquals(List.of("MAX changed from 3 to 5"), changed,
                "the unchanged NAME must not be reported alongside the changed MAX");
    }

    @Test
    void unchangedConstantsStayQuiet() {
        byte[] bytecode = classWithConstants("MAX", 3, "NAME", "reclazz");
        assertTrue(ConstantChangeWarning.changedConstants(bytecode, bytecode).isEmpty());
    }

    @Test
    void aConstantThatBecomesNonConstantStaysQuiet() {
        // Turning MAX into a computed field removes its ConstantValue; the
        // initialiser story is the added-static machinery's, not this one's.
        byte[] before = classWithConstants("MAX", 3);
        byte[] after = classWithConstants("OTHER", 9);

        assertTrue(ConstantChangeWarning.changedConstants(before, after).isEmpty(),
                "a disappeared ConstantValue is not a changed constant");
    }

    @Test
    void stringConstantsAreQuotedAndLongOnesTrimmed() {
        byte[] before = classWithConstants("GREETING", "old");
        byte[] after = classWithConstants("GREETING", "x".repeat(60));

        List<String> changed = ConstantChangeWarning.changedConstants(before, after);
        assertEquals(1, changed.size());
        assertTrue(changed.get(0).startsWith("GREETING changed from \"old\" to \"xxx"),
                changed.get(0));
        assertTrue(changed.get(0).endsWith("...\""), "long strings are trimmed: " + changed.get(0));
    }

    // ── fixture bytecode ──────────────────────────────────────────────────

    private static byte[] classWithConstants(Object... namesAndValues) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Constants", null,
                "java/lang/Object", null);
        for (int i = 0; i < namesAndValues.length; i += 2) {
            Object value = namesAndValues[i + 1];
            String descriptor = value instanceof String ? "Ljava/lang/String;" : "I";
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    (String) namesAndValues[i], descriptor, null, value).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
