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

    /**
     * The class this warning matters most on is the one it used to miss
     * entirely. A holder whose only members are constants is never loaded:
     * every use of it was inlined, so nothing at runtime refers to it, so the
     * transformer never sees it and there is no cached previous version to
     * compare against. Measured on a live Spring Boot server, editing exactly
     * such a class produced no warning at all.
     *
     * <p>With nothing to compare against, guessing which constant moved would
     * be a guess; naming the ones it declares is not, and it is enough to go
     * looking for the sources that read them.
     */
    @Test
    void aNeverLoadedHolderIsReportedWithoutABaseline() {
        ConstantChangeWarning.forget();
        byte[] holder = constantHolder("Limits", "MAX_RETRIES", 3);

        List<String> names = ConstantChangeWarning.check(
                "Limits", "Limits", holder, false);

        assertEquals(List.of("MAX_RETRIES"), names);
    }

    /** A class the JVM holds has a cache behind it; silence there is right. */
    @Test
    void aLoadedClassWithNoBaselineSaysNothing() {
        ConstantChangeWarning.forget();
        byte[] holder = constantHolder("Loaded", "MAX_RETRIES", 3);

        assertTrue(ConstantChangeWarning.check("Loaded", "Loaded", holder, true).isEmpty(),
                "an evicted cache must not turn into a warning about every constant");
    }

    /**
     * The save after the first one has something to compare against, so it
     * answers with what actually moved rather than everything declared.
     */
    @Test
    void theSecondSaveOfAHolderIsAnExactDiff() {
        ConstantChangeWarning.forget();
        ConstantChangeWarning.check("Limits2", "Limits2",
                constantHolder("Limits2", "MAX_RETRIES", 3), false);

        List<String> names = ConstantChangeWarning.check("Limits2", "Limits2",
                constantHolder("Limits2", "MAX_RETRIES", 9), false);

        assertEquals(List.of("MAX_RETRIES"), names);
    }

    /** And a save that changed nothing about the constants says nothing. */
    @Test
    void aHolderSavedWithTheSameValuesIsSilent() {
        ConstantChangeWarning.forget();
        ConstantChangeWarning.check("Limits3", "Limits3",
                constantHolder("Limits3", "MAX_RETRIES", 3), false);

        assertTrue(ConstantChangeWarning.check("Limits3", "Limits3",
                constantHolder("Limits3", "MAX_RETRIES", 3), false).isEmpty(),
                "a recompile that moved no value must not send anybody rebuilding");
    }

    /** A class with no compile-time constants has nothing to do with any of this. */
    @Test
    void aClassWithoutConstantsIsNeverReported() {
        ConstantChangeWarning.forget();
        org.objectweb.asm.ClassWriter writer =
                new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "Plain", null, "java/lang/Object", null);
        writer.visitEnd();

        assertTrue(ConstantChangeWarning.check("Plain", "Plain", writer.toByteArray(), false)
                .isEmpty());
    }

    private static byte[] constantHolder(String name, String field, int value) {
        org.objectweb.asm.ClassWriter writer =
                new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                name, null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC
                | org.objectweb.asm.Opcodes.ACC_STATIC
                | org.objectweb.asm.Opcodes.ACC_FINAL, field, "I", null, value).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
