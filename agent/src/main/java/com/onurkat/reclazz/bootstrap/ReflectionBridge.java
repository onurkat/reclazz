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

    private static final ConcurrentHashMap<String, ClassReflectionState> states = new ConcurrentHashMap<>();

    /**
     * Get all declared methods for a class, merging original + added methods.
     * Called from bytecode-rewritten call sites (replaces Class.getDeclaredMethods()).
     */
    public static Method[] getDeclaredMethods(Class<?> clazz) {
        String key = clazz.getName().replace('.', '/');
        ClassReflectionState state = states.get(key);

        Method[] original = clazz.getDeclaredMethods();

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
        ClassReflectionState state = states.get(key);

        Field[] original = clazz.getDeclaredFields();

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
        String key = clazz.getName().replace('.', '/');
        ClassReflectionState state = states.get(key);

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
        String key = clazz.getName().replace('.', '/');
        ClassReflectionState state = states.get(key);

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
     * Atomically replace all added members for a class.
     * Avoids the window where clearClass + re-add leaves an incomplete state.
     */
    public static void replaceClassState(String internalClassName,
                                          List<Method> methods, List<Field> fields) {
        ClassReflectionState newState = new ClassReflectionState();
        newState.addedMethods.addAll(methods);
        newState.addedFields.addAll(fields);
        states.put(internalClassName, newState);
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
    static final class ClassReflectionState {
        final List<Method> addedMethods = new CopyOnWriteArrayList<>();
        final List<Field> addedFields = new CopyOnWriteArrayList<>();
    }
}
