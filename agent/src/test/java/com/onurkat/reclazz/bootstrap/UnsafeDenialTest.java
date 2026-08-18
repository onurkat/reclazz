/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The door this feature depends on is being closed, on a published schedule.
 *
 * <p>JEP 471 deprecated the memory-access methods in {@code sun.misc.Unsafe}
 * for removal: a warning from JDK 24, an exception by default in JDK 26,
 * removal after that. Appending an enum constant needs those methods, because
 * writing a final field has no supported alternative; every candidate was
 * measured and a VarHandle from {@code unreflectVarHandle} answers
 * {@code UnsupportedOperationException} for a final field, with or without
 * {@code setAccessible}, instance and static alike.
 *
 * <p>What was wrong was not the dependency but the failure. Run today with
 * {@code --sun-misc-unsafe-memory-access=deny}, which is JDK 26's behaviour
 * brought forward, the refusal escaped as:
 *
 * <pre>
 *   [ERR] Hot-swap failed for Status: Structural reload failed: staticFieldBase
 * </pre>
 *
 * <p>The whole class reload lost, including a method body changed in the same
 * save that had nothing to do with the enum, and a JDK internal name put in
 * front of a developer as though it meant something. Measured again after the
 * change, the unrelated method reloaded and the enum declined with a sentence
 * naming the policy and the flag.
 */
class UnsafeDenialTest {

    /**
     * Not probed at startup on purpose: the probe is itself a call, and on JDK
     * 24 and 25 a call is what prints the warning that names Reclazz to the
     * developer. An application that never reloads an enum must never see it.
     */
    @Test
    void availabilityIsNotProbedEagerly() throws IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/bootstrap/UnsafeAccess.java"));

        int findEnd = source.indexOf("static boolean isAvailable()");
        String beforeFirstUse = source.substring(0, findEnd);
        for (String memoryAccess : new String[]{
                "staticFieldBase", "staticFieldOffset", "objectFieldOffset", "putObject"}) {
            assertFalse(beforeFirstUse.contains("UNSAFE." + memoryAccess),
                    memoryAccess + " must not be called while resolving availability, "
                    + "or every startup prints the deprecation warning");
        }
    }

    /** A refusal has to be permanent, or every reload asks again and warns again. */
    @Test
    void aRefusalIsRememberedAndTurnsAvailabilityOff() throws IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/bootstrap/UnsafeAccess.java"));

        assertTrue(source.contains("denied = true"), "the refusal has to be recorded");
        assertTrue(source.contains("UNSAFE != null && !denied"),
                "and availability has to consult it");
    }

    /**
     * Every accessor has to convert the refusal, or the JDK's own wording
     * reaches the developer as the reason their reload failed.
     */
    @Test
    void everyAccessorConvertsTheRefusal() throws IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/bootstrap/UnsafeAccess.java"));

        int accessors = source.split("UNSAFE\\.", -1).length - 1;
        int conversions = source.split("throw refused\\(t\\)", -1).length - 1;
        assertTrue(conversions >= 6,
                "each accessor needs its own conversion; found " + conversions
                + " for " + accessors + " Unsafe calls");
    }

    /**
     * The append reads the constant array before it writes anything, and that
     * read is a memory access too. It sat outside the try, which is exactly
     * how the refusal escaped as a failed class reload.
     */
    @Test
    void theFirstReadIsInsideTheGuard() throws IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/bootstrap/EnumSurgery.java"));

        int tryAt = source.indexOf("List<String> appended = new ArrayList<>();");
        int firstRead = source.indexOf("UnsafeAccess.getStatic(valuesField)");
        assertTrue(firstRead > tryAt,
                "reading $VALUES before the try is what let a refusal fail the whole reload");
        assertTrue(source.contains("catch (UnsafeAccess.MemoryAccessUnavailable e)"),
                "and the refusal needs its own branch, with its own sentence");
    }

    /** The sentence has to name the policy and the way to reproduce it. */
    @Test
    void theMessageExplainsTheJdkPolicy() throws IOException {
        List<String> text = stringsIn("com/onurkat/reclazz/bootstrap/EnumSurgery");

        assertTrue(text.stream().anyMatch(t -> t.contains("JDK 26 refuses it by default")),
                "a developer meeting this on a new JDK should not have to guess why");
        // The flag a developer needs here is the one that gives the feature
        // back, not the one that reproduces the refusal. Reproducing is our
        // problem and lives in the changelog; theirs is a server that stopped
        // picking up an enum value.
        assertTrue(text.stream().anyMatch(t -> t.contains("--sun-misc-unsafe-memory-access=allow")),
                "the way out has to be in the message, not only the diagnosis");
        assertTrue(text.stream().anyMatch(t -> t.contains("Nothing was changed")),
                "a half-applied enum is the one outcome worse than none");
    }

    private static List<String> stringsIn(String internalName) throws IOException {
        try (InputStream in = UnsafeDenialTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "cannot read " + internalName);
            ClassReader reader = new ClassReader(in.readAllBytes());
            List<String> out = new ArrayList<>();
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
