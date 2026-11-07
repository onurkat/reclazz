/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.util;

import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/** Ownership of prepared opaque Backoffice definitions. Collected entries remain tombstones. */
public final class BackofficePreparedOwners {
    private static final ConcurrentHashMap<String, Owner> OWNERS = new ConcurrentHashMap<>();
    private static final class Owner {
        final WeakReference<ClassLoader> loader;
        volatile WeakReference<Class<?>> type = new WeakReference<>(null);
        boolean foreignReported;
        Owner(ClassLoader loader) { this.loader = new WeakReference<>(loader); }
        synchronized void foreign(String name) {
            if (!foreignReported) {
                foreignReported = true;
                StatusReporter.warn("Backoffice class " + name.replace('/', '.')
                        + " has another definition; it is left unprepared and is not a reload target");
            }
        }
    }
    private BackofficePreparedOwners() {}
    public static boolean contains(String internalName) { return OWNERS.containsKey(internalName); }
    public static boolean matches(String internalName, ClassLoader loader) {
        Owner owner = OWNERS.get(internalName);
        boolean matches = owner != null && loader != null && owner.loader.get() == loader;
        if (owner != null && !matches) owner.foreign(internalName);
        return matches;
    }
    /** Called under the admission lock only after a successful initial transform. */
    public static void record(String internalName, ClassLoader loader) {
        OWNERS.putIfAbsent(internalName, new Owner(loader));
    }
    /** Never invokes a loader: a reload must target an existing definition. */
    public static Class<?> resolve(String internalName, Instrumentation instrumentation) {
        Owner owner = OWNERS.get(internalName);
        if (owner == null) return null;
        try {
            ClassLoader loader = owner.loader.get();
            if (loader == null) throw unavailable();
            Class<?> cached = owner.type.get();
            if (cached != null && cached.getClassLoader() == loader) return cached;
            if (instrumentation != null) {
                Class<?> found = null;
                for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    if (!internalName.equals(type.getName().replace('.', '/'))) continue;
                    if (type.getClassLoader() == loader) found = type;
                    else owner.foreign(internalName);
                }
                if (found != null) {
                    owner.type = new WeakReference<>(found);
                    return found;
                }
            }
        } catch (LinkageError failure) {
            throw new IllegalStateException("Backoffice owner loader unavailable; reload not applied", failure);
        }
        throw unavailable();
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Backoffice owner loader unavailable; reload not applied");
    }
}
