/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * The same memory writes, through a door JEP 471 does not close.
 *
 * <p>{@link UnsafeAccess} needs to write a final field and allocate an
 * instance without a constructor, and it has done both through
 * {@code sun.misc.Unsafe}. That class is on a published removal schedule: a
 * warning from JDK 24, an exception by default in JDK 26, removal after that.
 * Read as "the feature ends in JDK 26", which is how it was written up, that
 * schedule is wrong in one specific way. What JEP 471 deprecates, and what
 * {@code --sun-misc-unsafe-memory-access} governs, is {@code sun.misc.Unsafe}.
 * The JDK's own copy, {@code jdk.internal.misc.Unsafe}, is what the JDK itself
 * uses for these operations, is not deprecated, and is not touched by that
 * flag. It is merely not exported.
 *
 * <p>Not exported is a problem an agent can solve, and this one already solves
 * it elsewhere: {@code ReflectionRootFilter} opens {@code jdk.internal.reflect}
 * to itself with {@link Instrumentation#redefineModule}. The same call opens
 * {@code jdk.internal.misc}. Measured on JDK 21, from a class on the bootstrap
 * class path, which is where these classes live:
 *
 * <pre>
 *   before open: InaccessibleObjectException: module java.base does not
 *                "exports jdk.internal.misc" to unnamed module
 *   after open : static final field written
 * </pre>
 *
 * <p>So this is the fallback, not the first choice. {@code sun.misc.Unsafe}
 * stays the door that gets tried first, because it needs no module surgery and
 * it is what every JDK in service today answers on. This one opens only when
 * that door has actually been refused, which means an application on a JDK
 * where nothing is refused never opens a JDK-internal package at all.
 *
 * <p>None of this is a supported API and it is not pretended to be. The
 * difference that matters is that a JDK removing {@code sun.misc.Unsafe} is
 * scheduled and a JDK removing its own {@code Unsafe} is not, so the feature
 * outlives the schedule instead of ending on it. Every shape here is resolved
 * reflectively and every failure is a decline, so a JDK that moves one of them
 * leaves the enum work reporting exactly what it reported before this existed.
 */
final class InternalUnsafe {

    /** Handed over at startup; without it there is no module to open with. */
    private static volatile Instrumentation instrumentation;

    /** Resolution is attempted once, and its outcome is what everything reads. */
    private static boolean resolved;
    private static Object unsafe;
    private static Method allocateInstance;
    private static Method staticFieldBase;
    private static Method staticFieldOffset;
    private static Method objectFieldOffset;
    private static Method getReference;
    private static Method putReference;
    private static Method putInt;
    private static Method storeFence;

    private InternalUnsafe() {
    }

    static void useForFallback(Instrumentation inst) {
        instrumentation = inst;
    }

    /**
     * Whether this door opens, resolved on the first ask.
     *
     * <p>Asking is itself the module open, so it happens when the fallback is
     * actually needed rather than at startup: an application that never
     * reloads an enum, or one whose first door was never refused, must not
     * have a JDK-internal package opened on its behalf.
     */
    static synchronized boolean isAvailable() {
        if (resolved) return unsafe != null;
        resolved = true;
        try {
            Instrumentation inst = instrumentation;
            if (inst == null) return false;

            inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
                    Map.of("jdk.internal.misc", Set.of(InternalUnsafe.class.getModule())),
                    Set.of(), Map.of());

            Class<?> type = Class.forName("jdk.internal.misc.Unsafe");
            Method getUnsafe = type.getDeclaredMethod("getUnsafe");
            getUnsafe.setAccessible(true);
            Object resolvedUnsafe = getUnsafe.invoke(null);

            allocateInstance = method(type, "allocateInstance", Class.class);
            staticFieldBase = method(type, "staticFieldBase", Field.class);
            staticFieldOffset = method(type, "staticFieldOffset", Field.class);
            objectFieldOffset = method(type, "objectFieldOffset", Field.class);
            // Renamed from getObject/putObject in JDK 12. Both spellings are
            // asked for so a JDK that kept the old one is not a decline.
            getReference = either(type, "getReference", "getObject",
                    Object.class, long.class);
            putReference = either(type, "putReference", "putObject",
                    Object.class, long.class, Object.class);
            putInt = method(type, "putInt", Object.class, long.class, int.class);
            storeFence = method(type, "storeFence");

            unsafe = resolvedUnsafe;
            return true;
        } catch (Throwable notThisJvm) {
            unsafe = null;
            return false;
        }
    }

    static Object allocateInstance(Class<?> type) throws InstantiationException {
        try {
            return allocateInstance.invoke(unsafe, type);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof InstantiationException cause) throw cause;
            throw UnsafeAccess.refused(e.getCause() == null ? e : e.getCause());
        } catch (Throwable t) {
            throw UnsafeAccess.refused(t);
        }
    }

    static Object getStatic(Field field) {
        try {
            return getReference.invoke(unsafe, staticFieldBase.invoke(unsafe, field),
                    staticFieldOffset.invoke(unsafe, field));
        } catch (Throwable t) {
            throw UnsafeAccess.refused(t);
        }
    }

    static void putStatic(Field field, Object value) {
        try {
            putReference.invoke(unsafe, staticFieldBase.invoke(unsafe, field),
                    staticFieldOffset.invoke(unsafe, field), value);
        } catch (Throwable t) {
            throw UnsafeAccess.refused(t);
        }
    }

    static Object getObject(Object target, Field field) {
        try {
            return getReference.invoke(unsafe, target, objectFieldOffset.invoke(unsafe, field));
        } catch (Throwable t) {
            throw UnsafeAccess.refused(t);
        }
    }

    static void putObject(Object target, Field field, Object value) {
        try {
            putReference.invoke(unsafe, target, objectFieldOffset.invoke(unsafe, field), value);
        } catch (Throwable t) {
            throw UnsafeAccess.refused(t);
        }
    }

    static void putInt(Object target, Field field, int value) {
        try {
            putInt.invoke(unsafe, target, objectFieldOffset.invoke(unsafe, field), value);
        } catch (Throwable t) {
            throw UnsafeAccess.refused(t);
        }
    }

    static void storeFence() {
        try {
            storeFence.invoke(unsafe);
        } catch (Throwable t) {
            throw UnsafeAccess.refused(t);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method found = owner.getMethod(name, parameters);
        found.setAccessible(true);
        return found;
    }

    private static Method either(Class<?> owner, String preferred, String older,
                                 Class<?>... parameters) throws NoSuchMethodException {
        try {
            return method(owner, preferred, parameters);
        } catch (NoSuchMethodException olderJdk) {
            return method(owner, older, parameters);
        }
    }
}
