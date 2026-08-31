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
 * <h2>This door is closing, and there is a second one</h2>
 *
 * <p>JEP 471 deprecated these methods for removal and is phasing them out: a
 * warning from JDK 24, an exception by default in JDK 26, removal after that.
 * The behaviour can be brought forward today with
 * {@code --sun-misc-unsafe-memory-access=deny}, which is how the handling here
 * was tested rather than reasoned about.
 *
 * <p>What that schedule ends is {@code sun.misc.Unsafe}. It is not what the
 * JDK uses for these operations itself: that is {@code jdk.internal.misc.Unsafe},
 * which is not deprecated, is not governed by that flag, and is merely not
 * exported. An agent can open it, and this one already opens
 * {@code jdk.internal.reflect} the same way. So a refusal here is a fallback
 * rather than an ending, and {@link InternalUnsafe} is where it goes.
 *
 * <p>The order is deliberate. {@code sun.misc.Unsafe} is tried first because
 * it needs no module surgery and every JDK in service today answers on it; the
 * second door opens only after the first has actually refused, so an
 * application on a JDK that refuses nothing never has a JDK-internal package
 * opened on its behalf.
 *
 * <p>So every accessor assumes it can fail. The first refusal is remembered, so
 * that a JVM which has closed this door is asked once and not once per reload,
 * and it is reported as {@link MemoryAccessUnavailable} rather than as whatever
 * internal name the JVM happened to use. Before this, the refusal surfaced as
 * <em>Structural reload failed: staticFieldBase</em>: the whole class reload
 * lost, including method bodies that had nothing to do with the enum, and a JDK
 * internal put in front of the developer as if it meant something to them.
 */
public final class UnsafeAccess {

    private static final sun.misc.Unsafe UNSAFE = find();

    /**
     * Set the first time this JVM refuses the first door. Not probed at
     * startup on purpose: probing is itself a call, and on JDK 24 and 25 a
     * call is what prints the warning. An application that never reloads an
     * enum should never see it.
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

    /**
     * The Instrumentation the fallback needs, handed over at startup.
     *
     * <p>Nothing is opened here. The module surgery happens the first time the
     * first door is actually refused, which on every JDK in service today is
     * never.
     */
    public static void useForFallback(java.lang.instrument.Instrumentation inst) {
        InternalUnsafe.useForFallback(inst);
    }

    static boolean isAvailable() {
        return (UNSAFE != null && !denied) || InternalUnsafe.isAvailable();
    }

    /** Whether both doors are shut, which is worth saying differently. */
    static boolean isDenied() {
        return !isAvailable();
    }

    static Object allocateInstance(Class<?> type) throws InstantiationException {
        if (firstDoorOpen()) {
            try {
                return UNSAFE.allocateInstance(type);
            } catch (InstantiationException e) {
                throw e;
            } catch (Throwable t) {
                fellBack(t);
            }
        }
        return InternalUnsafe.allocateInstance(type);
    }

    static Object getStatic(Field field) {
        if (firstDoorOpen()) {
            try {
                return UNSAFE.getObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field));
            } catch (Throwable t) {
                fellBack(t);
            }
        }
        return InternalUnsafe.getStatic(field);
    }

    static void putStatic(Field field, Object value) {
        if (firstDoorOpen()) {
            try {
                UNSAFE.putObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field), value);
                return;
            } catch (Throwable t) {
                fellBack(t);
            }
        }
        InternalUnsafe.putStatic(field, value);
    }

    static void putObject(Object target, Field field, Object value) {
        if (firstDoorOpen()) {
            try {
                UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
                return;
            } catch (Throwable t) {
                fellBack(t);
            }
        }
        InternalUnsafe.putObject(target, field, value);
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
        if (firstDoorOpen()) {
            try {
                UNSAFE.storeFence();
                return;
            } catch (Throwable t) {
                fellBack(t);
            }
        }
        InternalUnsafe.storeFence();
    }

    static void putInt(Object target, Field field, int value) {
        if (firstDoorOpen()) {
            try {
                UNSAFE.putInt(target, UNSAFE.objectFieldOffset(field), value);
                return;
            } catch (Throwable t) {
                fellBack(t);
            }
        }
        InternalUnsafe.putInt(target, field, value);
    }

    /** The array an EnumMap or EnumSet captured when it was built. */
    static Object getObject(Object target, Field field) {
        if (firstDoorOpen()) {
            try {
                return UNSAFE.getObject(target, UNSAFE.objectFieldOffset(field));
            } catch (Throwable t) {
                fellBack(t);
            }
        }
        return InternalUnsafe.getObject(target, field);
    }

    /** Whether the first door is worth trying: present, and not already refused. */
    private static boolean firstDoorOpen() {
        return UNSAFE != null && !denied;
    }

    /**
     * The first door has refused. That is permanent for this JVM, so it is
     * remembered: asking again cannot succeed and every ask is another warning
     * in the developer's log. Then the second door is tried, and only if that
     * one is shut too does the caller get a refusal.
     */
    private static void fellBack(Throwable t) {
        denied = true;
        if (!InternalUnsafe.isAvailable()) {
            throw refused(t);
        }
    }

    /**
     * The refusal callers see, with the JDK's own internal name kept out of
     * it. Package-private because the second door reports through it too.
     */
    static MemoryAccessUnavailable refused(Throwable cause) {
        return new MemoryAccessUnavailable(cause);
    }
}
