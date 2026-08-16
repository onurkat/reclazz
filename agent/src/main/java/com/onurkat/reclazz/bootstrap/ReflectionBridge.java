/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Metadata registry that merges original + structurally-added Method/Field objects.
 * Frameworks like Spring MVC call Class.getDeclaredMethods() internally — without
 * this bridge they would never see methods added via structural reload.
 *
 * ReflectionInterceptTransformer rewrites call sites so that
 * Class.getDeclaredMethods() → ReflectionBridge.getDeclaredMethods(), etc.
 *
 * BOOTSTRAP CLASS: Must have ZERO dependencies outside java.* packages.
 */
public final class ReflectionBridge {

    /**
     * Added members, held on the class they belong to.
     *
     * This was a map keyed by class name, and it was never pruned. The values
     * are Method and Field objects, each holding its declaring class, which
     * holds the classloader that defined it, so every class that ever had a
     * member added stayed loaded for the life of the JVM together with
     * everything its loader had defined. A weak reference alongside the members
     * would have changed nothing, because the members themselves are what pins
     * the class.
     *
     * A ClassValue is stored on the class, so it cannot outlive it, and the
     * lookup is the same one the callers already do: every entry point here
     * receives the Class.
     */
    private static final ClassValue<ClassReflectionState> states = new ClassValue<>() {
        @Override
        protected ClassReflectionState computeValue(Class<?> type) {
            return new ClassReflectionState();
        }
    };

    /**
     * The prefix on everything Reclazz writes into a user's class: the
     * {@code __reclazz$ext} instance array, the {@code __reclazz$lookup}
     * static handle, and the {@code __reclazz$v0$...} copies of renamed
     * original methods.
     *
     * None of it is the user's code and none of it may be visible to
     * reflection. A framework that walks members does not know to skip it,
     * and it does not have to: SAP Commerce's OCC layer collects every
     * declared field of every DTO with no filter on synthetic or static,
     * then feeds each field's type to JAXB. That dragged
     * {@code MethodHandles$Lookup} into the mapping set, JAXB followed it
     * into JDK internals, and building the context failed. Every OCC
     * response became an empty 400 for as long as the agent was attached.
     */
    private static final String INTERNAL_PREFIX = "__reclazz$";

    static boolean isInternal(String memberName) {
        return memberName.startsWith(INTERNAL_PREFIX);
    }

    /**
     * Both hide methods return the array unchanged when there is nothing to
     * strip. Reflection over members is hot enough that allocating a copy per
     * call, for the many classes Reclazz never touched, would be a poor trade.
     */
    private static Method[] hideInternal(Method[] methods) {
        int keep = 0;
        for (Method m : methods) {
            if (!isInternal(m.getName())) keep++;
        }
        if (keep == methods.length) return methods;

        Method[] visible = new Method[keep];
        int i = 0;
        for (Method m : methods) {
            if (!isInternal(m.getName())) visible[i++] = m;
        }
        return visible;
    }

    private static Field[] hideInternal(Field[] fields) {
        int keep = 0;
        for (Field f : fields) {
            if (!isInternal(f.getName())) keep++;
        }
        if (keep == fields.length) return fields;

        Field[] visible = new Field[keep];
        int i = 0;
        for (Field f : fields) {
            if (!isInternal(f.getName())) visible[i++] = f;
        }
        return visible;
    }

    /**
     * Get all declared methods for a class, merging original + added methods.
     * Called from bytecode-rewritten call sites (replaces Class.getDeclaredMethods()).
     */
    public static Method[] getDeclaredMethods(Class<?> clazz) {
        String key = clazz.getName().replace('.', '/');
        Members state = stateFor(clazz);

        Method[] original = hideInternal(clazz.getDeclaredMethods());

        if (state == null || state.addedMethods.isEmpty()) {
            return original;
        }

        // Merge: original + added
        List<Method> merged = new ArrayList<>(original.length + state.addedMethods.size());
        for (Method m : original) {
            merged.add(m);
        }
        merged.addAll(state.addedMethods);
        return merged.toArray(new Method[0]);
    }

    /**
     * Get all declared fields for a class, merging original + added fields.
     */
    public static Field[] getDeclaredFields(Class<?> clazz) {
        String key = clazz.getName().replace('.', '/');
        Members state = stateFor(clazz);

        Field[] original = hideInternal(clazz.getDeclaredFields());

        if (state == null || state.addedFields.isEmpty()) {
            return original;
        }

        List<Field> merged = new ArrayList<>(original.length + state.addedFields.size());
        for (Field f : original) {
            merged.add(f);
        }
        merged.addAll(state.addedFields);
        return merged.toArray(new Field[0]);
    }

    /**
     * Get a specific declared method by name and parameter types.
     * Checks added methods first, then falls back to the original class.
     */
    public static Method getDeclaredMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        if (isInternal(name)) throw new NoSuchMethodException(name);
        String key = clazz.getName().replace('.', '/');
        Members state = stateFor(clazz);

        // Check added methods first
        if (state != null) {
            for (Method m : state.addedMethods) {
                if (m.getName().equals(name) && paramTypesMatch(m.getParameterTypes(), parameterTypes)) {
                    return m;
                }
            }
        }

        // Fall back to original
        return clazz.getDeclaredMethod(name, parameterTypes);
    }

    /**
     * Get a specific declared field by name.
     * Checks added fields first, then falls back to the original class.
     */
    public static Field getDeclaredField(Class<?> clazz, String name)
            throws NoSuchFieldException {
        if (isInternal(name)) throw new NoSuchFieldException(name);
        String key = clazz.getName().replace('.', '/');
        Members state = stateFor(clazz);

        // Check added fields first
        if (state != null) {
            for (Field f : state.addedFields) {
                if (f.getName().equals(name)) {
                    return f;
                }
            }
        }

        // Fall back to original
        return clazz.getDeclaredField(name);
    }

    /**
     * The public counterpart of {@link #getDeclaredMethods}, merging in
     * structurally-added methods.
     *
     * Frameworks reach for both forms. Spring's own reflection utilities
     * favour the declared variants, but plenty of code, including parts of
     * Spring, calls {@code getMethods()}, and until this existed those call
     * sites saw the class exactly as it was compiled.
     *
     * Only public added members are merged, because that is the contract of
     * {@code Class.getMethods()} and code that filters on modifiers
     * afterwards would otherwise see something impossible.
     */
    public static Method[] getMethods(Class<?> clazz) {
        Method[] original = hideInternal(clazz.getMethods());
        Members state = stateFor(clazz);
        if (state == null || state.addedMethods.isEmpty()) {
            return original;
        }

        List<Method> merged = new ArrayList<>(original.length + state.addedMethods.size());
        for (Method m : original) {
            merged.add(m);
        }
        for (Method m : state.addedMethods) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                merged.add(m);
            }
        }
        return merged.toArray(new Method[0]);
    }

    /** The public counterpart of {@link #getDeclaredFields}. */
    public static Field[] getFields(Class<?> clazz) {
        Field[] original = hideInternal(clazz.getFields());
        Members state = stateFor(clazz);
        if (state == null || state.addedFields.isEmpty()) {
            return original;
        }

        List<Field> merged = new ArrayList<>(original.length + state.addedFields.size());
        for (Field f : original) {
            merged.add(f);
        }
        for (Field f : state.addedFields) {
            if (java.lang.reflect.Modifier.isPublic(f.getModifiers())) {
                merged.add(f);
            }
        }
        return merged.toArray(new Field[0]);
    }

    /**
     * The public counterpart of {@link #getDeclaredMethod}. Falls back to the
     * original class, which also walks the hierarchy, so an inherited method
     * still resolves exactly as before.
     */
    public static Method getMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        if (isInternal(name)) throw new NoSuchMethodException(name);
        Members state = stateFor(clazz);
        if (state != null) {
            for (Method m : state.addedMethods) {
                if (java.lang.reflect.Modifier.isPublic(m.getModifiers())
                        && m.getName().equals(name)
                        && paramTypesMatch(m.getParameterTypes(), parameterTypes)) {
                    return m;
                }
            }
        }
        return clazz.getMethod(name, parameterTypes);
    }

    /** The public counterpart of {@link #getDeclaredField}. */
    public static Field getField(Class<?> clazz, String name) throws NoSuchFieldException {
        if (isInternal(name)) throw new NoSuchFieldException(name);
        Members state = stateFor(clazz);
        if (state != null) {
            for (Field f : state.addedFields) {
                if (java.lang.reflect.Modifier.isPublic(f.getModifiers())
                        && f.getName().equals(name)) {
                    return f;
                }
            }
        }
        return clazz.getField(name);
    }

    /**
     * The state for a class, dropping it if the class it describes is gone.
     *
     * Pruning on read is enough: an entry nobody reads costs one map slot, and
     * the classloader it was holding is released the moment its last reader
     * looks.
     */
    private static Members stateFor(Class<?> clazz) {
        return states.get(clazz).members;
    }

    /**
     * Atomically replace all added members for a class.
     * Avoids the window where clearClass + re-add leaves an incomplete state.
     */
    public static void replaceClassState(Class<?> owner,
                                         List<Method> methods, List<Field> fields) {
        states.get(owner).members = new Members(methods, fields);
    }

    private static boolean paramTypesMatch(Class<?>[] a, Class<?>[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /**
     * Per-class state tracking added methods and fields.
     * Uses CopyOnWriteArrayList for thread safety — reads (getDeclaredMethods)
     * can happen concurrently with writes (registerAddedMethod/clearClass).
     */
    /**
     * The mutable holder a ClassValue needs, since a ClassValue itself cannot
     * be reassigned. One volatile field, replaced whole.
     */
    static final class ClassReflectionState {
        volatile Members members = Members.EMPTY;
    }

    /**
     * What a reload added, as one immutable set.
     *
     * Replaced rather than edited in place: a reader that caught a clear
     * followed by a re-add would see a class with half its members, and that
     * window is what a framework scan would report as a missing method.
     */
    static final class Members {
        static final Members EMPTY = new Members(List.of(), List.of());

        final List<Method> addedMethods;
        final List<Field> addedFields;

        Members(List<Method> methods, List<Field> fields) {
            this.addedMethods = List.copyOf(methods);
            this.addedFields = List.copyOf(fields);
        }
    }
}
