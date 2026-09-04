/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reaching into a class this agent did not compile against.
 *
 * <p>Nearly everything the framework reloaders do begins here: find a method or
 * a field that Spring, Hibernate or the platform declares somewhere up a
 * hierarchy, make it accessible, and use it. It was written six times, under
 * five names, and the copies had drifted in the way copies do. Four let anything
 * other than a missing member escape; one turned it into a null. One stopped at
 * {@code Object}, the others did not. One did not walk at all: it used
 * {@code getMethod}, so it saw public methods on interfaces that the walking
 * copies missed and missed the private ones they found, and twelve call sites
 * were quietly relying on that difference.
 *
 * <p>The policy, once, in one place. A member that is not there is null, and so
 * is one this JVM will not open: a reloader asking whether a field exists is
 * asking a question it can be told no to, and an exception out of a lookup
 * turns "this Spring version does not have that field" into a failed reload.
 * The walk goes to the top, because {@code getDeclaredMethod} does not look at
 * supertypes and the method wanted is usually on the abstract class two levels
 * up: asking the concrete class for {@code detectHandlerMethods} threw on every
 * single MVC re-scan until somebody noticed.
 *
 * <p>Deliberately not a home for every reflective read in this agent. Several
 * of them look for a field by shape rather than by name, refuse when two match,
 * or treat a null value as an error, and those are different questions wearing
 * similar code.
 */
public final class Reflect {

    private Reflect() {
    }

    /**
     * A method declared anywhere from {@code type} upward, made accessible.
     *
     * @return null when no class in the hierarchy declares it, or when this JVM
     *         will not open it
     */
    public static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method found = declaredOn(current, name, parameterTypes);
            if (found != null) return found;
        }
        // Then the interfaces, because one of the six copies this replaced used
        // getMethod and its callers were relying on default methods: Spring's
        // own configuration types declare plenty. A superclass walk alone finds
        // strictly more than getMethod for classes and strictly less for
        // interfaces, and a shared helper has to be a superset of both.
        return onInterfaces(type, name, parameterTypes);
    }

    private static Method onInterfaces(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> face : current.getInterfaces()) {
                Method found = declaredOn(face, name, parameterTypes);
                if (found != null) return found;
                found = onInterfaces(face, name, parameterTypes);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Method declaredOn(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException notOnThisOne) {
            return null;
        } catch (Throwable notOpenable) {
            return null;
        }
    }

    /** A field declared anywhere from {@code type} upward, made accessible. */
    public static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException notOnThisOne) {
                // The next class up may declare it.
            } catch (Throwable notOpenable) {
                return null;
            }
        }
        return null;
    }

    /** What {@code target} holds in that field, or null if it has no such field. */
    public static Object readField(Object target, String name) {
        if (target == null) return null;
        Field field = findField(target.getClass(), name);
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (Throwable notReadable) {
            return null;
        }
    }

    /**
     * Puts a value in that field.
     *
     * @return whether it went in, which is what the callers report on: a cache
     *         that was not cleared is the difference between a reload landing
     *         and a reload that only looks like it did
     */
    public static boolean writeField(Object target, String name, Object value) {
        if (target == null) return false;
        Field field = findField(target.getClass(), name);
        if (field == null) return false;
        try {
            field.set(target, value);
            return true;
        } catch (Throwable notWritable) {
            return false;
        }
    }
}
