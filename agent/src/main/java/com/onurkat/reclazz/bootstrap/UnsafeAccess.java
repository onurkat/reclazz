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
 * the two things {@code Unsafe} is here for. Both are unavoidable: an enum
 * constant is a final static holding an object whose constructor is private and
 * whose {@code name} and {@code ordinal} are final, and the JVM will not run
 * the class initialiser that would have set them a second time.
 *
 * <p>It is kept in one class so there is one place to check whether it is
 * available and one place to change if a JVM closes the door. Everything that
 * uses it asks {@link #isAvailable()} first and declines rather than throwing,
 * because the feature this serves is optional and the application is not.
 */
final class UnsafeAccess {

    private static final sun.misc.Unsafe UNSAFE = find();

    private UnsafeAccess() {
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
        return UNSAFE != null;
    }

    static Object allocateInstance(Class<?> type) throws InstantiationException {
        return UNSAFE.allocateInstance(type);
    }

    static Object getStatic(Field field) {
        return UNSAFE.getObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field));
    }

    static void putStatic(Field field, Object value) {
        UNSAFE.putObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field), value);
    }

    static void putObject(Object target, Field field, Object value) {
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
    }

    static void putInt(Object target, Field field, int value) {
        UNSAFE.putInt(target, UNSAFE.objectFieldOffset(field), value);
    }

    /** The array an EnumMap or EnumSet captured when it was built. */
    static Object getObject(Object target, Field field) {
        return UNSAFE.getObject(target, UNSAFE.objectFieldOffset(field));
    }
}
