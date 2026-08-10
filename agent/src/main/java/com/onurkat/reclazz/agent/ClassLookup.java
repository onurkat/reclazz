/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import java.lang.instrument.Instrumentation;

/**
 * Shared utility for finding loaded classes across different classloaders.
 * Consolidates the duplicated findLoadedClass logic from ClassReloader,
 * StructuralReloader, and ReclazzAgent.
 */
public final class ClassLookup {

    private ClassLookup() {}

    /**
     * Find a class that has already been loaded by any classloader.
     * Uses Class.forName with the context classloader first (fast path),
     * falls back to system classloader, then scans all loaded classes
     * via Instrumentation to cover custom classloader hierarchies.
     */
    public static Class<?> findLoadedClass(String className, Instrumentation instrumentation) {
        // Fast path: try context classloader
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) return Class.forName(className, false, cl);
        } catch (ClassNotFoundException ignored) {}

        // Fallback: try system classloader
        try {
            return Class.forName(className, false, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException ignored) {}

        // Final fallback: scan all loaded classes via Instrumentation
        if (instrumentation != null) {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (className.equals(loaded.getName())) return loaded;
            }
        }

        return null;
    }
}
