/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Adding an enum constant to a running JVM.
 *
 * <p>The constant is a static field holding an instance built in
 * {@code <clinit>}, and {@code values()} hands back a copy of a private array
 * built there too. The JVM runs {@code <clinit>} once, so neither appears by
 * itself: on a stock JDK the redefinition is refused outright, and on an
 * enhanced-redefinition VM it is accepted and the field is left null.
 *
 * <p>What this class does was measured before it was written, on JDK 21 and on
 * JDK 25, on a stock JVM and on JetBrains Runtime. Building the instance,
 * growing the private array and clearing the two caches on {@code Class} makes
 * {@code values()} and {@code valueOf} correct. Growing every
 * {@code $SwitchMap} array in the loaded classes turns the
 * ArrayIndexOutOfBoundsException that a switch would otherwise throw into the
 * default branch, which is the right answer for code compiled before the
 * constant existed.
 *
 * <h2>Why every step is checked first</h2>
 *
 * <p>All of this reaches into shapes the JDK never promised: {@code $VALUES},
 * {@code Class.enumConstants}, {@code Class.enumConstantDirectory}. They have
 * held across the releases tested, and they are still not a contract. So
 * nothing is written until every piece has been found and identified, and if
 * any one of them is missing or the wrong shape, the whole thing is declined
 * and the reload falls back to saying a restart is needed. A JDK that renames
 * one of these makes Reclazz decline; it does not make Reclazz corrupt an enum
 * and report success.
 *
 * <h2>Appending only</h2>
 *
 * <p>A constant added at the end takes the next ordinal and nothing that
 * already exists moves. A constant inserted in the middle, or one removed,
 * shifts the ordinals of the constants after it, and every structure indexed by
 * ordinal is then silently wrong: maps and sets already in memory, and every
 * value a database holds for an {@code @Enumerated} column, whose default is
 * ORDINAL. No amount of array growing repairs that, so it is refused rather
 * than attempted. The caller decides by comparing the two constant lists.
 */
public final class EnumSurgery {

    private EnumSurgery() {
    }

    /** What was done, or why nothing was. */
    public record Outcome(List<String> appended, String declinedBecause) {

        public boolean applied() {
            return declinedBecause == null;
        }

        public static Outcome declined(String why) {
            return new Outcome(List.of(), why);
        }
    }

    /**
     * Append constants to a loaded enum.
     *
     * @param enumClass the loaded enum
     * @param names     the new constants, in declaration order, each taking the
     *                  next ordinal after the ones already there
     */
    public static Outcome append(Class<?> enumClass, List<String> names) {
        if (enumClass == null || !enumClass.isEnum() || names == null || names.isEmpty()) {
            return Outcome.declined("nothing to append");
        }
        if (!UnsafeAccess.isAvailable()) {
            return Outcome.declined("this JVM does not expose the memory access this needs");
        }

        // An enum whose class initialiser has not run yet has a null constant
        // array, and appending to that would build a half-formed enum that the
        // real initialiser then overwrites. Asking for the constants is what
        // runs it, and it is a no-op for the usual case where the application
        // has been using the enum all along.
        Object[] initialised = enumClass.getEnumConstants();
        if (initialised == null || initialised.length == 0) {
            return Outcome.declined("the enum has no constants yet, so there is nothing to append to");
        }

        // Everything is located before anything is written, so a JDK whose
        // internals have moved leaves the enum exactly as it was.
        Field valuesField = findValuesArray(enumClass);
        if (valuesField == null) {
            return Outcome.declined("the enum's private constant array was not found "
                    + "(this JDK names it differently)");
        }
        Field nameField = declaredField(Enum.class, "name");
        Field ordinalField = declaredField(Enum.class, "ordinal");
        if (nameField == null || ordinalField == null) {
            return Outcome.declined("java.lang.Enum does not have the expected name/ordinal fields");
        }
        Field constantsCache = declaredField(Class.class, "enumConstants");
        Field directoryCache = declaredField(Class.class, "enumConstantDirectory");
        if (constantsCache == null || directoryCache == null) {
            return Outcome.declined("java.lang.Class does not have the expected enum caches");
        }

        // From here on every read and write can be refused by the JVM, and from
        // JDK 26 it is refused by default. One try covers all of them so that a
        // refusal declines the append instead of failing the whole class
        // reload: the method bodies in the same save have nothing to do with
        // the enum and used to be lost along with it.
        List<String> appended = new ArrayList<>();
        try {
            Object[] existing = (Object[]) UnsafeAccess.getStatic(valuesField);
            if (existing == null) {
                return Outcome.declined("the enum's constant array is empty after initialisation");
            }
            if (!existing.getClass().getComponentType().equals(enumClass)) {
                return Outcome.declined("the enum's constant array is not the expected type");
            }

            Object[] grown = (Object[]) Array.newInstance(enumClass, existing.length + names.size());
            System.arraycopy(existing, 0, grown, 0, existing.length);

            for (int i = 0; i < names.size(); i++) {
                Object constant = UnsafeAccess.allocateInstance(enumClass);
                UnsafeAccess.putObject(constant, nameField, names.get(i));
                UnsafeAccess.putInt(constant, ordinalField, existing.length + i);
                grown[existing.length + i] = constant;

                // The static field exists only where the redefinition was
                // accepted. Where it was not, reads of it are rewritten to the
                // store, so that is where the value has to go.
                Field constantField = declaredField(enumClass, names.get(i));
                if (constantField != null) {
                    UnsafeAccess.putStatic(constantField, constant);
                } else {
                    FieldStore.putStaticExtField(enumClass.getName().replace('.', '/'),
                            names.get(i), "L" + enumClass.getName().replace('.', '/') + ";", constant);
                }
                appended.add(names.get(i));
            }

            UnsafeAccess.putStatic(valuesField, grown);

            // values() copies the array, valueOf reads a map, and both are
            // cached on the Class after the first call.
            UnsafeAccess.putObject(enumClass, constantsCache, null);
            UnsafeAccess.putObject(enumClass, directoryCache, null);
        } catch (UnsafeAccess.MemoryAccessUnavailable e) {
            return Outcome.declined("this JVM does not allow the memory access an enum "
                    + "append needs. JDK 26 refuses it by default, and any release can be "
                    + "run that way with --sun-misc-unsafe-memory-access=deny. Nothing was "
                    + "changed");
        } catch (Throwable t) {
            return Outcome.declined("the constant could not be built: " + t);
        }

        return new Outcome(appended, null);
    }

    /**
     * Grow every switch table that was sized for the old constant count.
     *
     * <p>javac compiles a switch over an enum into a lookup through a synthetic
     * {@code int[]} indexed by ordinal, sized when the class holding it was
     * initialised. A new constant indexes past the end, and the
     * ArrayIndexOutOfBoundsException surfaces in application code that has
     * nothing to do with the reload. The new slot is left at zero, which is the
     * value javac uses for "not one of the cases I know", so the switch takes
     * its default branch: exactly what code compiled before the constant
     * existed should do.
     *
     * @param loadedClasses every class the JVM has, from Instrumentation
     * @return how many tables were grown
     */
    public static int growSwitchTables(Class<?>[] loadedClasses, Class<?> enumClass, int newLength) {
        if (loadedClasses == null || enumClass == null || !UnsafeAccess.isAvailable()) return 0;
        // A JVM that has already refused will refuse every one of these, and
        // each ask is another warning in the developer's log.

        // $SwitchMap$com$example$Status for com.example.Status; the mangling
        // replaces each dot with a dollar.
        String suffix = "$SwitchMap$" + enumClass.getName().replace('.', '$');
        int grown = 0;

        for (Class<?> candidate : loadedClasses) {
            Field[] fields;
            try {
                fields = candidate.getDeclaredFields();
            } catch (Throwable t) {
                continue;                       // a class this JVM will not describe
            }
            for (Field field : fields) {
                if (!field.getName().equals(suffix)) continue;
                if (!int[].class.equals(field.getType())) continue;
                try {
                    int[] table = (int[]) UnsafeAccess.getStatic(field);
                    if (table == null || table.length >= newLength) continue;
                    int[] wider = new int[newLength];
                    System.arraycopy(table, 0, wider, 0, table.length);
                    UnsafeAccess.putStatic(field, wider);
                    grown++;
                } catch (UnsafeAccess.MemoryAccessUnavailable e) {
                    // Refused once means refused for the rest of this JVM's
                    // life; carrying on would print a warning per class.
                    return grown;
                } catch (Throwable ignored) {
                    // One table that will not grow is one switch that still
                    // throws; the rest are worth doing anyway.
                }
            }
        }
        return grown;
    }

    /**
     * The compiler-generated array holding an enum's constants.
     *
     * <p>javac calls it {@code $VALUES}, and it is the only private static
     * field of the enum's own array type, which is what this matches on. A JDK
     * or a compiler that names it differently still produces a field of that
     * shape; one that changes the shape makes this return null and the whole
     * append decline.
     */
    private static Field findValuesArray(Class<?> enumClass) {
        Field byName = declaredField(enumClass, "$VALUES");
        if (byName != null && byName.getType().equals(Array.newInstance(enumClass, 0).getClass())) {
            return byName;
        }
        Field found = null;
        try {
            for (Field field : enumClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().isArray()) continue;
                if (!enumClass.equals(field.getType().getComponentType())) continue;
                if (found != null) return null;      // ambiguous: decline rather than guess
                found = field;
            }
        } catch (Throwable t) {
            return null;
        }
        return found;
    }

    private static Field declaredField(Class<?> owner, String name) {
        try {
            return owner.getDeclaredField(name);
        } catch (Throwable t) {
            return null;
        }
    }
}
