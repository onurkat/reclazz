/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What to say when the compiler is newer than the bytecode library.
 *
 * <p>Reclazz reads and writes class files with ASM, and ASM learns a class file
 * version after the JDK that introduced it ships. So there is always a window,
 * once a year, where a developer on the newest JDK compiles to a version this
 * build cannot read. It is not a bug that gets fixed once; it is a thing that
 * happens every September, and what matters is what the developer sees when it
 * does.
 *
 * <p>What they saw was ASM's own sentence, once per watched class, which on a
 * real application is a page of identical red at startup:
 *
 * <pre>
 *   [ERR] Transform failed for com.acme.Order: Unsupported class file major version 70
 * </pre>
 *
 * <p>That names a number rather than a JDK, repeats itself for every class, and
 * says nothing about what to do. It is also, taken at face value, alarming in
 * the wrong direction: the application is fine and most reloading still works,
 * because the JVM reads those classes perfectly well and a method body change
 * goes through {@code redefineClasses} without ASM being involved. What is lost
 * is instrumentation, and with it the structural half.
 *
 * <p>So it is said once, in terms of the Java release rather than the header
 * number, with both ways out. The highest supported version is read off ASM
 * itself rather than written down here, so this stays true the next time the
 * dependency is bumped instead of becoming a comment that used to be right.
 */
public final class ClassFileVersionGuard {

    /** Java 1.0 is 45, and every release since adds one. */
    private static final int FIRST_MAJOR = 45;

    private static final AtomicBoolean reported = new AtomicBoolean(false);

    /** Classes skipped for this reason, so a later refusal can give it. */
    private static final Set<String> skipped = ConcurrentHashMap.newKeySet();

    private static volatile int highestSupported = 0;

    private ClassFileVersionGuard() {
    }

    /** The class file version in this buffer, or 0 when it is not a class file. */
    public static int majorOf(byte[] bytecode) {
        if (bytecode == null || bytecode.length < 8) return 0;
        boolean isClassFile = (bytecode[0] & 0xFF) == 0xCA && (bytecode[1] & 0xFF) == 0xFE
                && (bytecode[2] & 0xFF) == 0xBA && (bytecode[3] & 0xFF) == 0xBE;
        if (!isClassFile) return 0;
        return ((bytecode[6] & 0xFF) << 8) | (bytecode[7] & 0xFF);
    }

    /**
     * The newest class file version the bundled ASM understands, read off its
     * own {@code Opcodes} constants. Asking rather than hard-coding is the
     * point: a version bump then moves this on its own.
     */
    public static int highestSupported() {
        int known = highestSupported;
        if (known != 0) return known;
        int highest = 0;
        for (Field field : Opcodes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != int.class) continue;
            if (!field.getName().matches("V\\d+(_PREVIEW)?")) continue;
            try {
                highest = Math.max(highest, field.getInt(null) & 0xFFFF);
            } catch (IllegalAccessException notReadable) {
                // A constant that will not be read is one this cannot count.
            }
        }
        // A build with no readable constants should decline to block anything.
        highestSupported = highest == 0 ? Integer.MAX_VALUE : highest;
        return highestSupported;
    }

    /** Whether these bytes are from a compiler this build cannot read. */
    public static boolean tooNew(byte[] bytecode) {
        int major = majorOf(bytecode);
        return major != 0 && major > highestSupported();
    }

    /**
     * Say it, once for the session, and remember the class so that a later
     * structural reload can give this reason instead of guessing at another.
     */
    public static void note(String className, byte[] bytecode) {
        if (className != null) skipped.add(className.replace('/', '.'));
        if (!reported.compareAndSet(false, true)) return;

        int major = majorOf(bytecode);
        StatusReporter.warn("This build reads class files up to Java " + javaRelease(highestSupported())
                + ", and these are Java " + javaRelease(major) + ". Your application runs and "
                + "method body changes still reload, because the JVM reads them and that path "
                + "does not go through the bytecode library. What needs the library is "
                + "instrumentation, so adding or removing members is off until one of two "
                + "things: compile with --release " + javaRelease(highestSupported())
                + " while you develop, or update Reclazz to a build that knows Java "
                + javaRelease(major) + ".");
    }

    /** Whether this class was left uninstrumented because it was too new. */
    public static boolean wasSkipped(String className) {
        return className != null && skipped.contains(className.replace('/', '.'));
    }

    /** Major 61 is Java 17, and it counts up from Java 1 at major 45. */
    static int javaRelease(int major) {
        return major - FIRST_MAJOR + 1;
    }

    /** Tests need a clean session; nothing in the agent calls this. */
    static void resetForTests() {
        reported.set(false);
        skipped.clear();
        highestSupported = 0;
    }
}
