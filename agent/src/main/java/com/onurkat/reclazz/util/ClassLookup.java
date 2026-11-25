/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

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
        public List<Class<?>> definitions(String name) { return List.copyOf(snapshot().names().getOrDefault(name,List.of())); }
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
        public Class<?> reloadTarget(String name, Function<String, LoaderCandidates> recordedLoaders) {
            if (instrumentation != null && snapshot == null) {
                return findLoadedClass(name, instrumentation, recordedLoaders);
            }
            return reloadTarget(name);
        }
        public Class<?> reloadTarget(String name) {
            if (BackofficePreparedOwners.contains(name.replace('.', '/'))) return find(name);
            List<Class<?>> matches = snapshot().names().getOrDefault(name, List.of());
            if (matches.size() > 1) throw new AmbiguousDefinition(name);
            return matches.isEmpty() ? null : matches.get(0);
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

    /** A name-only observation has no defining loader and leaves the index incomplete. */
    public record LoaderCandidates(Set<ClassLoader> loaders, boolean hasUnlocatedDefinition) { }

    private static final Method LOADED_IN_LOADER = lookupMethod();

    private static Method lookupMethod() {
        try {
            return ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        } catch (NoSuchMethodException | SecurityException unavailable) {
            return null;
        }
    }

    private static Class<?> recordedDefinition(String name, ClassLoader loader, Instrumentation inst) {
        // ReflectionRootFilter already opens java.lang to the agent at startup.
        // An embedded reloader without that opening queries only this loader's initiated classes.
        if (loader != null && LOADED_IN_LOADER != null) {
            try {
                if (LOADED_IN_LOADER.canAccess(loader) || LOADED_IN_LOADER.trySetAccessible()) {
                    Class<?> type = (Class<?>) LOADED_IN_LOADER.invoke(loader, name);
                    return type != null && type.getClassLoader() == loader ? type : null;
                }
            } catch (ReflectiveOperationException | SecurityException unavailable) {
                // Use the supported instrumentation query below.
            }
        }
        for (Class<?> type : inst.getInitiatedClasses(loader)) {
            if (type.getClassLoader() == loader && type.getName().equals(name)) return type;
        }
        return null;
    }

    public static Class<?> findLoadedClass(String name, Instrumentation inst,
                                           Function<String, LoaderCandidates> recordedLoaders) {
        String internal = name.replace('.', '/');
        if (recordedLoaders == null || inst == null || BackofficePreparedOwners.contains(internal)) {
            return findLoadedClass(name, inst);
        }
        LoaderCandidates recorded = recordedLoaders.apply(internal);
        if (recorded.loaders().isEmpty()) return findLoadedClass(name, inst);
        Set<ClassLoader> loaders = recorded.loaders();
        if (recorded.hasUnlocatedDefinition()) {
            // A raw class may predate observation. Probe the live context loader chain
            // without defining anything before paying for a VM-wide fallback.
            loaders = Collections.newSetFromMap(new IdentityHashMap<>());
            loaders.addAll(recorded.loaders());
            for (ClassLoader loader = Thread.currentThread().getContextClassLoader();
                 loader != null; loader = loader.getParent()) {
                loaders.add(loader);
            }
        }
        Class<?> found = null;
        for (ClassLoader loader : loaders) {
            Class<?> candidate = recordedDefinition(name, loader, inst);
            if (candidate == null) continue;
            if (found != null && candidate != found) throw new AmbiguousDefinition(name);
            found = candidate;
        }
        return recorded.hasUnlocatedDefinition() ? findLoadedClass(name, inst) : found;
    }

    /** This query must never cause the VM to define the class it is looking for. */
    public static Class<?> findLoadedClass(String className, Instrumentation instrumentation) {
        String internal = className.replace('.', '/');
        if (BackofficePreparedOwners.contains(internal)) return BackofficePreparedOwners.resolve(internal, instrumentation);
        Class<?> found = null;
        if (instrumentation != null) {
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (!className.equals(type.getName())) continue;
                if (found != null && found != type) throw new AmbiguousDefinition(className);
                found = type;
            }
        }
        return found;
    }

    public static final class AmbiguousDefinition extends IllegalStateException {
        public AmbiguousDefinition(String name) {
            super(name + " has multiple defining class loaders; restart to apply");
        }
    }
}
