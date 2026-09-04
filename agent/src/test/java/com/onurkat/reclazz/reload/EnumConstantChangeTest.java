/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An enum constant added by a reload, and the engine that used to stay quiet
 * about it.
 *
 * <p>On a stock JDK the redefinition is refused and the companion engine said
 * so. On JetBrains Runtime with enhanced class redefinition the reloader is
 * switched off entirely, the JVM accepts the redefinition, adds the field and
 * leaves it null, and the log read {@code [SWAP] Reloaded Status (61ms)} while
 * {@code values()} still returned the old set and {@code valueOf} still threw.
 * The most capable configuration was the silent one, which is the same shape as
 * the interface and the JPA gaps: measured, reproducible, and reported as
 * success.
 *
 * <p>Conjuring the constant up was tried before deciding not to. Allocating the
 * instance, enlarging the private array and clearing the caches on
 * {@code Class} does make {@code values()} and {@code valueOf} work, and then
 * every {@code EnumMap} and {@code EnumSet} built before the reload throws
 * {@code ArrayIndexOutOfBoundsException} and every switch takes its default
 * branch. Those live on the heap and in other classes' static arrays, so the
 * honest move is the sentence, not the surgery.
 */
class EnumConstantChangeTest {

    @Test
    void anAddedConstantIsDetected() throws IOException {
        var change = EnumConstantChange.check(Status.class, bytecodeOf(StatusPlusOne.class));

        assertNotNull(change, "this is the case the class exists for");
        assertEquals(List.of("SHIPPED"), change.added());
        assertTrue(change.removed().isEmpty());
        assertTrue(change.describe().contains("SHIPPED"), change.describe());
    }

    /**
     * Removing one is unappliable in the same way and in the opposite
     * direction: the constant stays in {@code values()} and stays valid to
     * {@code valueOf} until a restart.
     */
    @Test
    void aRemovedConstantIsDetectedToo() throws IOException {
        var change = EnumConstantChange.check(Status.class, bytecodeOf(StatusMinusOne.class));

        assertNotNull(change);
        assertEquals(List.of("PAID"), change.removed());
        assertTrue(change.describe().contains("lost"), change.describe());
    }

    @Test
    void anUnchangedEnumIsSilent() throws IOException {
        assertNull(EnumConstantChange.check(Status.class, bytecodeOf(Status.class)));
    }

    /**
     * An enum whose method bodies changed is an ordinary reload, and a
     * persistence-style warning on it would be noise.
     */
    @Test
    void anEnumWithTheSameConstantsIsNotReported() throws IOException {
        assertNull(EnumConstantChange.check(Status.class, bytecodeOf(StatusEditedBody.class)),
                "same constants, different body: nothing here needs a restart");
    }

    @Test
    void anOrdinaryClassIsNotAnEnum() throws IOException {
        assertNull(EnumConstantChange.check(NotAnEnum.class, bytecodeOf(StatusPlusOne.class)));
    }

    /**
     * Enhanced redefinition adds the field, so a check taken afterwards sees a
     * loaded class that already matches the payload. Both call sites compare
     * first for that reason.
     */
    /**
     * The claim about a second, batch reload path came out with the path: it
     * was never called by anything, and this was asserting the ordering inside
     * a method nothing ran, which is how dead code comes to look maintained.
     */
    @Test
    void theReloadEngineComparesBeforeRedefining() throws IOException {
        String classReloader = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/agent/ClassReloader.java"));

        int checkAt = classReloader.indexOf("EnumConstantChange.check(existingClass, newBytecode)");
        int redefineAt = classReloader.indexOf("instrumentation.redefineClasses(definition)");
        assertTrue(checkAt > 0, "the JetBrains Runtime path is the one that was silent");
        assertTrue(checkAt < redefineAt,
                "checking after the redefine hides it on exactly the VM that accepts it");
    }

    /** One sentence, so the two engines cannot drift apart. */
    @Test
    void theMessageLivesInOnePlace() throws IOException {
        assertTrue(stringsIn("com/onurkat/reclazz/reload/EnumConstantChange").stream()
                        .anyMatch(t -> t.contains("Enum constants cannot be added")),
                "the wording belongs to the shared helper");

        assertFalse(stringsIn("com/onurkat/reclazz/reload/StructuralReloader").stream()
                        .anyMatch(t -> t.contains("Enum constants cannot be added")),
                "a second copy in the companion engine is how the two answers "
                + "stop matching after the next edit");
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    enum Status { NEW, PAID }

    enum StatusPlusOne { NEW, PAID, SHIPPED }

    enum StatusMinusOne { NEW }

    @SuppressWarnings("unused")
    enum StatusEditedBody {
        NEW, PAID;

        String label() { return "edited"; }
    }

    static class NotAnEnum { }

    private static byte[] bytecodeOf(Class<?> c) throws IOException {
        try (InputStream in = c.getClassLoader()
                .getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            assertNotNull(in, "cannot read " + c.getName());
            return in.readAllBytes();
        }
    }

    private static List<String> stringsIn(String internalName) throws IOException {
        try (InputStream in = EnumConstantChangeTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "cannot read " + internalName);
            org.objectweb.asm.ClassReader reader =
                    new org.objectweb.asm.ClassReader(in.readAllBytes());
            List<String> out = new java.util.ArrayList<>();
            char[] buffer = new char[reader.getMaxStringLength()];
            for (int i = 1; i < reader.getItemCount(); i++) {
                int offset = reader.getItem(i);
                if (offset == 0) continue;
                try {
                    if (reader.readByte(offset - 1) == 8) {
                        Object value = reader.readConst(i, buffer);
                        if (value instanceof String s) out.add(s);
                    }
                } catch (RuntimeException ignored) {
                    // not every pool slot reads back as a constant
                }
            }
            return out;
        }
    }
}
