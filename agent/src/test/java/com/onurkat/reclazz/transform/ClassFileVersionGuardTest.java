/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The yearly problem: a JDK ships, a developer compiles to it, and the bytecode
 * library this build was cut with has never heard of that version.
 *
 * <p>It is not a defect to be fixed once. ASM learns a class file version after
 * the JDK that introduced it ships, so the window reopens every September, and
 * the only thing that can be got right in advance is what the developer sees
 * when it does.
 */
class ClassFileVersionGuardTest {

    @BeforeEach
    void freshSession() {
        ClassFileVersionGuard.resetForTests();
    }

    private static byte[] classAt(int major) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(major, Opcodes.ACC_PUBLIC, "app/Sample", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void readsTheVersionOutOfTheHeader() {
        assertEquals(61, ClassFileVersionGuard.majorOf(classAt(61)));
        assertEquals(Opcodes.V17, ClassFileVersionGuard.majorOf(classAt(Opcodes.V17)));
    }

    @Test
    void somethingThatIsNotAClassFileHasNoVersion() {
        assertEquals(0, ClassFileVersionGuard.majorOf(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        assertEquals(0, ClassFileVersionGuard.majorOf(new byte[]{(byte) 0xCA, (byte) 0xFE}));
        assertEquals(0, ClassFileVersionGuard.majorOf(null));
    }

    /**
     * Read off ASM rather than written down, so a dependency bump moves it and
     * this test keeps meaning the same thing.
     */
    @Test
    void theCeilingComesFromTheLibraryItself() {
        int highest = ClassFileVersionGuard.highestSupported();

        assertTrue(highest >= Opcodes.V21,
                "a build that cannot read Java 21 cannot do the job at all, saw " + highest);
        assertFalse(ClassFileVersionGuard.tooNew(classAt(highest)),
                "the highest supported version is supported");
        assertTrue(ClassFileVersionGuard.tooNew(classAt(highest + 1)),
                "one past the ceiling is over it");
    }

    @Test
    void everythingWeCanReadIsLetThrough() {
        assertFalse(ClassFileVersionGuard.tooNew(classAt(Opcodes.V17)));
        assertFalse(ClassFileVersionGuard.tooNew(classAt(Opcodes.V21)));
        assertFalse(ClassFileVersionGuard.tooNew(new byte[]{1, 2, 3}),
                "bytes that are not a class file are somebody else's problem, not too new");
    }

    @Test
    void aVersionTranslatesToTheJavaReleaseADeveloperKnows() {
        assertEquals(17, ClassFileVersionGuard.javaRelease(61));
        assertEquals(21, ClassFileVersionGuard.javaRelease(65));
        assertEquals(26, ClassFileVersionGuard.javaRelease(70));
    }

    /**
     * A class skipped for this reason is remembered, because the refusal a
     * structural reload gives later would otherwise blame the wrong thing and
     * send the developer to restart, which is the one thing that cannot help.
     */
    @Test
    void aSkippedClassIsRememberedSoTheReasonSurvives() {
        int tooNew = ClassFileVersionGuard.highestSupported() + 1;

        assertFalse(ClassFileVersionGuard.wasSkipped("app.Sample"));
        ClassFileVersionGuard.note("app/Sample", classAt(tooNew));

        assertTrue(ClassFileVersionGuard.wasSkipped("app.Sample"));
        assertTrue(ClassFileVersionGuard.wasSkipped("app/Sample"),
                "the internal name and the source name are the same class");
        assertFalse(ClassFileVersionGuard.wasSkipped("app.Other"));
    }
}
