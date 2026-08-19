/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.Field;

/**
 * The narrow set of memory writes the enum work needs, behind one door.
 *
 * <p>Writing a final field and allocating an instance without a constructor are
 * the two things {@code Unsafe} is here for. Both are unavoidable for that
 * feature: an enum constant is a final static holding an object whose
 * constructor is private and whose {@code name} and {@code ordinal} are final,
 * and the JVM will not run the class initialiser that would have set them a
 * second time. Every supported alternative was measured and none of them
 * writes a final field: a VarHandle from {@code unreflectVarHandle} answers
 * {@code UnsupportedOperationException} for a final field, with or without
 * {@code setAccessible}, for instance and static fields alike.
 *
 * <h2>This door is closing</h2>
 *
 * <p>JEP 471 deprecated these methods for removal and is phasing them out: a
 * warning from JDK 24, an exception by default in JDK 26, removal after that.
 * The behaviour can be brought forward today with
 * {@code --sun-misc-unsafe-memory-access=deny}, which is how the handling here
 * was tested rather than reasoned about.
 *
 * <p>So every accessor assumes it can fail. The first refusal is remembered, so
 * that a JVM which has closed this door is asked once and not once per reload,
 * and it is reported as {@link MemoryAccessUnavailable} rather than as whatever
 * internal name the JVM happened to use. Before this, the refusal surfaced as
 * <em>Structural reload failed: staticFieldBase</em>: the whole class reload
 * lost, including method bodies that had nothing to do with the enum, and a JDK
 * internal put in front of the developer as if it meant something to them.
 */
final class UnsafeAccess {

    private static final sun.misc.Unsafe UNSAFE = find();

    /**
     * Set the first time this JVM refuses. Not probed at startup on purpose:
     * probing is itself a call, and on JDK 24 and 25 a call is what prints the
     * warning. An application that never reloads an enum should never see it.
     */
    private static volatile boolean denied = false;

    private UnsafeAccess() {
    }

    /** Thrown when this JVM will not do the memory access, rather than a JDK internal. */
    static final class MemoryAccessUnavailable extends RuntimeException {
        MemoryAccessUnavailable(Throwable cause) {
            super("this JVM does not allow the memory access this needs", cause);
        }
    }

    private static sun.misc.Unsafe find() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isAvailable() {
        return UNSAFE != null && !denied;
    }

    /** Whether this JVM has already refused, which is worth saying differently. */
    static boolean isDenied() {
        return denied;
    }

    static Object allocateInstance(Class<?> type) throws InstantiationException {
        try {
            return UNSAFE.allocateInstance(type);
        } catch (InstantiationException e) {
            throw e;
        } catch (Throwable t) {
            throw refused(t);
        }
    }

    static Object getStatic(Field field) {
        try {
            return UNSAFE.getObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field));
        } catch (Throwable t) {
            throw refused(t);
        }
    }

    static void putStatic(Field field, Object value) {
        try {
            UNSAFE.putObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field), value);
        } catch (Throwable t) {
            throw refused(t);
        }
    }

    static void putObject(Object target, Field field, Object value) {
        try {
            UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
        } catch (Throwable t) {
            throw refused(t);
        }
    }

    /**
     * A release fence, so plain stores made just before it are visible to
     * another thread that reads the same locations afterwards. Used to publish
     * an appended enum's grown array and cleared caches safely rather than
     * relying on unrelated fences on the reload path. {@code storeFence} is an
     * ordinary intrinsic, not a memory-access method, so it is outside JEP
     * 471's deprecation; it is guarded the same way only for uniformity.
     */
    static void storeFence() {
        try {
            UNSAFE.storeFence();
        } catch (Throwable t) {
            throw refused(t);
        }
    }

    static void putInt(Object target, Field field, int value) {
        try {
            UNSAFE.putInt(target, UNSAFE.objectFieldOffset(field), value);
        } catch (Throwable t) {
            throw refused(t);
        }
    }

    /** The array an EnumMap or EnumSet captured when it was built. */
    static Object getObject(Object target, Field field) {
        try {
            return UNSAFE.getObject(target, UNSAFE.objectFieldOffset(field));
        } catch (Throwable t) {
            throw refused(t);
        }
    }

    /**
     * A refusal is permanent for this JVM, so it is remembered: asking again
     * cannot succeed and every ask is another warning in the developer's log.
     */
    private static MemoryAccessUnavailable refused(Throwable cause) {
        denied = true;
        return new MemoryAccessUnavailable(cause);
    }
}
