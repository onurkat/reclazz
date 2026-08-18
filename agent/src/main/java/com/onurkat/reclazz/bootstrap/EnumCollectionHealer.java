/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * The EnumMap and EnumSet instances that already existed when a constant was
 * appended.
 *
 * <p>Both capture the enum's constant array when they are built and size their
 * storage from it. A map or set created before the append therefore holds an
 * array one slot short, and using the new constant with it throws
 * ArrayIndexOutOfBoundsException from inside {@code java.util}, in application
 * code with no visible connection to the reload. Measured, before this class
 * existed:
 *
 * <pre>
 *   old EnumMap.put(SHIPPED)  ArrayIndexOutOfBoundsException: Index 2 out of bounds for length 2
 *   old EnumSet.add(SHIPPED)  ArrayIndexOutOfBoundsException: Index 2 out of bounds for length 2
 * </pre>
 *
 * <p>Instances already on the heap cannot be found: the JVM offers a Java agent
 * no way to walk them. So they are healed where they are used instead. A call
 * to {@link #heal} is injected at the head of the instance methods of
 * {@code EnumMap} and the {@code EnumSet} implementations, and it does nothing
 * at all until an append has happened and nothing again unless this particular
 * instance is short.
 *
 * <p>The injection is installed lazily, the first time a constant is actually
 * appended. An application that never reloads an enum runs untouched
 * {@code java.util} code, which is the only honest default for a development
 * tool that transforms somebody else's collections.
 *
 * <p>What this cannot do is repair an ordinal that moved. Growing an array puts
 * the new constant in a new slot; a constant inserted in the middle renumbers
 * the ones after it, and every value already stored under the old numbering is
 * then in the wrong slot. That is refused before any of this runs.
 */
public final class EnumCollectionHealer {

    /**
     * Nothing has been appended yet, so nothing needs healing. Checked first on
     * every injected call, which is why an application that never reloads an
     * enum pays for a static read and nothing else.
     */
    private static volatile boolean anyAppendHappened = false;

    private static final Field ENUM_MAP_KEY_TYPE = field("java.util.EnumMap", "keyType");
    private static final Field ENUM_MAP_UNIVERSE = field("java.util.EnumMap", "keyUniverse");
    private static final Field ENUM_MAP_VALS = field("java.util.EnumMap", "vals");
    private static final Field ENUM_SET_TYPE = field("java.util.EnumSet", "elementType");
    private static final Field ENUM_SET_UNIVERSE = field("java.util.EnumSet", "universe");

    private EnumCollectionHealer() {
    }

    /** Whether the shapes this needs are all present on this JDK. */
    public static boolean isSupported() {
        return UnsafeAccess.isAvailable()
                && ENUM_MAP_KEY_TYPE != null && ENUM_MAP_UNIVERSE != null && ENUM_MAP_VALS != null
                && ENUM_SET_TYPE != null && ENUM_SET_UNIVERSE != null;
    }

    /** Called once an append has actually been applied. */
    public static void armed() {
        anyAppendHappened = true;
    }

    /**
     * Bring one instance up to the enum's current size, if it is behind.
     *
     * <p>Injected at the head of {@code java.util} methods, so it must never
     * throw and must be cheap in the overwhelmingly common case where there is
     * nothing to do.
     */
    public static void heal(Object collection) {
        if (!anyAppendHappened || collection == null) return;
        try {
            if (collection instanceof java.util.EnumMap) {
                healMap(collection);
            } else if (collection instanceof java.util.EnumSet) {
                healSet(collection);
            }
        } catch (Throwable ignored) {
            // A collection that will not be healed throws the same exception it
            // would have thrown anyway. Throwing a different one from inside
            // java.util would be worse than the problem.
        }
    }

    private static void healMap(Object map) {
        Class<?> keyType = (Class<?>) UnsafeAccess.getObject(map, ENUM_MAP_KEY_TYPE);
        Object[] current = constantsOf(keyType);
        if (current == null) return;

        Object[] universe = (Object[]) UnsafeAccess.getObject(map, ENUM_MAP_UNIVERSE);
        if (universe == null || universe.length >= current.length) return;

        Object[] vals = (Object[]) UnsafeAccess.getObject(map, ENUM_MAP_VALS);
        UnsafeAccess.putObject(map, ENUM_MAP_UNIVERSE, current);
        if (vals != null) {
            UnsafeAccess.putObject(map, ENUM_MAP_VALS, Arrays.copyOf(vals, current.length));
        }
    }

    private static void healSet(Object set) {
        Class<?> elementType = (Class<?>) UnsafeAccess.getObject(set, ENUM_SET_TYPE);
        Object[] current = constantsOf(elementType);
        if (current == null) return;

        Object[] universe = (Object[]) UnsafeAccess.getObject(set, ENUM_SET_UNIVERSE);
        if (universe == null || universe.length >= current.length) return;

        // EnumSet's universe is declared as Enum<?>[]; a Status[] would be
        // rejected by the field's type, so it is copied into the right one.
        Object[] widened = (Object[]) Array.newInstance(
                universe.getClass().getComponentType(), current.length);
        System.arraycopy(current, 0, widened, 0, current.length);
        UnsafeAccess.putObject(set, ENUM_SET_UNIVERSE, widened);
    }

    /** The enum's constants as they are now, after any append. */
    private static Object[] constantsOf(Class<?> type) {
        if (type == null || !type.isEnum()) return null;
        Object[] constants = type.getEnumConstants();
        return (constants == null || constants.length == 0) ? null : constants;
    }

    private static Field field(String owner, String name) {
        try {
            return Class.forName(owner).getDeclaredField(name);
        } catch (Throwable t) {
            return null;
        }
    }
}
