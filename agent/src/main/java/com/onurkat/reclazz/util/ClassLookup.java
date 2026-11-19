/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import java.lang.instrument.Instrumentation;
import java.util.*;

/**
 * Shared utility for finding loaded classes across different classloaders.
 * Consolidates the duplicated findLoadedClass logic from ClassReloader,
 * StructuralReloader, and ReclazzAgent.
 */
public final class ClassLookup {

    private ClassLookup() {}

    /** A bounded census for preparation: neither lookup invokes a class loader. */
    public static LoadedClasses loadedClasses(Instrumentation instrumentation) {
        return new LoadedClasses(instrumentation, new Class<?>[0]);
    }
    public static LoadedClasses loadedClasses(Class<?>[] classes) { return new LoadedClasses(null,classes); }
    public static final class LoadedClasses {
        private final Instrumentation instrumentation;
        private final Class<?>[] supplied;
        private record Snapshot(Class<?>[] classes, Map<String,List<Class<?>>> names) { }
        private volatile Snapshot snapshot;
        private LoadedClasses(Instrumentation instrumentation, Class<?>[] classes) {
            this.instrumentation=instrumentation;this.supplied=classes.clone();
        }
        private Snapshot snapshot() {
            Snapshot value=snapshot;
            if (value==null) synchronized (this) {
                value=snapshot;
                if (value==null) {
                    Class<?>[] classes=instrumentation==null?supplied:instrumentation.getAllLoadedClasses().clone();
                    Map<String,List<Class<?>>> names=new HashMap<>();
                    for (Class<?> type:classes) names.computeIfAbsent(type.getName(),ignored->new ArrayList<>()).add(type);
                    snapshot=value=new Snapshot(classes,names);
                }
            }
            return value;
        }
        public Class<?>[] all() { return snapshot().classes().clone(); }
        /** Admission may legitimately define classes after preparation took its census. */
        public LoadedClasses fresh() { return instrumentation == null ? this : loadedClasses(instrumentation); }
        public Class<?> find(String name) {
            List<Class<?>> matches=snapshot().names().getOrDefault(name,List.of());
            String internal=name.replace('.','/');
            if (BackofficePreparedOwners.contains(internal)) {
                for (Class<?> type:matches) if (BackofficePreparedOwners.matches(internal,type.getClassLoader())) return type;
                throw new IllegalStateException("Backoffice owner loader unavailable; reload not applied");
            }
            Class<?> found=find(name,Thread.currentThread().getContextClassLoader());
            if (found==null) found=find(name,ClassLoader.getSystemClassLoader());
            return found!=null?found:matches.isEmpty()?null:matches.get(0);
        }
        /** Only a loaded definition visible through this initiating loader's parent chain. */
        public Class<?> find(String name,ClassLoader loader) {
            List<Class<?>> matches=snapshot().names().getOrDefault(name,List.of());
            for (ClassLoader current=loader;;current=current.getParent()) {
                for (Class<?> type:matches) if (type.getClassLoader()==current) return type;
                if (current==null) return null;
            }
        }
    }

    /**
     * Find a class that has already been loaded by any classloader.
     * Uses Class.forName with the context classloader first (fast path),
     * falls back to system classloader, then scans all loaded classes
     * via Instrumentation to cover custom classloader hierarchies.
     */
    public static Class<?> findLoadedClass(String className, Instrumentation instrumentation) {
        String internalName = className.replace('.', '/');
        if (com.onurkat.reclazz.util.BackofficePreparedOwners.contains(internalName)) {
            return com.onurkat.reclazz.util.BackofficePreparedOwners.resolve(internalName, instrumentation);
        }
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
