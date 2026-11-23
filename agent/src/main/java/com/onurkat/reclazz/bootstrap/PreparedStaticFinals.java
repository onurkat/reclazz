/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Static final fields that resetStatics prepared by removing {@code final} at first load.
 *
 * <p>Two facts are kept per class and never mixed: which fields are physically non-final because of the first-load mask
 * ({@link #isPrepared}), and which of those the current source still declares final ({@link #modifiers}). Reflection reports
 * the current declaration, so a later save that removes {@code final}, or removes the field and adds it back without it,
 * is reported as non-final again (audit F1). The hooked {@code Field.getModifiers()} path takes no lock: one ClassValue
 * lookup and a map read, and a descriptor comparison only for a field whose name matches (audit F3).
 */
public final class PreparedStaticFinals {
    private PreparedStaticFinals() { }

    /** Per class: name -> descriptor, immutable maps replaced on update. */
    private static final class Owner {
        final Map<String, String> prepared;
        volatile Map<String, String> declaredFinal;
        Owner(Map<String, String> prepared) { this.prepared = prepared; this.declaredFinal = prepared; }
    }

    private static final class LoaderKey extends WeakReference<ClassLoader> {
        final int hash;
        LoaderKey(ClassLoader loader, ReferenceQueue<ClassLoader> queue) { super(loader, queue); hash = System.identityHashCode(loader); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof LoaderKey key && get() != null && get() == key.get();
        }
    }
    private static final ReferenceQueue<ClassLoader> COLLECTED = new ReferenceQueue<>();
    /** First-load masks, recorded before the Class exists; read once per class by OWNERS. */
    private static final Map<LoaderKey, Map<String, Map<String, String>>> PENDING = new HashMap<>();
    private static volatile boolean any;

    private static final ClassValue<Owner> OWNERS = new ClassValue<>() {
        @Override protected Owner computeValue(Class<?> type) {
            ClassLoader loader = type.getClassLoader();
            if (loader == null) return new Owner(Map.of());
            synchronized (PENDING) {
                purge();
                Map<String, Map<String, String>> owners = PENDING.get(new LoaderKey(loader, null));
                Map<String, String> fields = owners == null ? null : owners.get(type.getName());
                return new Owner(fields == null ? Map.of() : fields);
            }
        }
    };

    /** Records the fields ({@code name:descriptor}) whose final bit was removed for {@code internalName} in {@code loader}. */
    public static void record(ClassLoader loader, String internalName, Set<String> definalized) {
        if (loader == null || definalized.isEmpty()) return;
        Map<String, String> fields = new HashMap<>();
        for (String key : definalized) { int colon = key.indexOf(':'); fields.put(key.substring(0, colon), key.substring(colon + 1)); }
        synchronized (PENDING) {
            purge();
            PENDING.computeIfAbsent(new LoaderKey(loader, COLLECTED), ignored -> new HashMap<>())
                    .put(internalName.replace('/', '.'), Map.copyOf(fields));
        }
        any = true;
    }

    /**
     * After an accepted save: the prepared fields the new source still declares {@code static final} ({@code name:descriptor}).
     * Fields that lost {@code final}, or were removed, are no longer reported final.
     */
    public static void declared(Class<?> owner, Set<String> stillFinal) {
        if (!any) return;
        Owner state = OWNERS.get(owner);
        if (state.prepared.isEmpty()) return;
        Map<String, String> next = new HashMap<>();
        for (var entry : state.prepared.entrySet())
            if (stillFinal.contains(entry.getKey() + ":" + entry.getValue())) next.put(entry.getKey(), entry.getValue());
        state.declaredFinal = Map.copyOf(next);
    }

    /** Whether this field is physically non-final only because resetStatics prepared it at first load. */
    public static boolean isPrepared(Field field) {
        if (!any) return false;
        String descriptor = OWNERS.get(field.getDeclaringClass()).prepared.get(field.getName());
        return descriptor != null && descriptor.equals(field.getType().descriptorString());
    }

    /** The modifiers reflection reports: physical ones, with {@code final} restored while the current source declares it. */
    public static int modifiers(Field field, int physical) {
        if ((physical & (Modifier.STATIC | Modifier.FINAL)) != Modifier.STATIC || !any) return physical;
        String descriptor = OWNERS.get(field.getDeclaringClass()).declaredFinal.get(field.getName());
        return descriptor != null && descriptor.equals(field.getType().descriptorString()) ? physical | Modifier.FINAL : physical;
    }

    /** Field's cached accessors, reached through the java.lang.reflect opening the reflection install grants. */
    private static final class Accessors {
        static final java.lang.invoke.VarHandle FIELD, OVERRIDE, ROOT;
        static {
            java.lang.invoke.VarHandle field = null, override = null, root = null;
            try {
                var lookup = java.lang.invoke.MethodHandles.privateLookupIn(Field.class, java.lang.invoke.MethodHandles.lookup());
                Class<?> accessor = Class.forName("jdk.internal.reflect.FieldAccessor", false, null);
                field = lookup.findVarHandle(Field.class, "fieldAccessor", accessor);
                override = lookup.findVarHandle(Field.class, "overrideFieldAccessor", accessor);
                root = lookup.findVarHandle(Field.class, "root", Field.class);
            } catch (ReflectiveOperationException | RuntimeException unavailable) { field = override = root = null; }
            FIELD = field; OVERRIDE = override; ROOT = root;
        }
    }

    /**
     * Called first in Field's accessor getters. A prepared field's declaration can change after a save, but the JDK caches the
     * read-only/writable decision in the accessor it built from the modifiers at that time, also on retained Field copies
     * (R31b audit round 2 F1a). So a prepared field never reuses a cached accessor: the JDK builds one from the current
     * declaration on each access. Fields that were not prepared return after the flag test.
     */
    public static void refreshAccessors(Field field) {
        if (!any || (field.getModifiers() & Modifier.STATIC) == 0 || Accessors.FIELD == null || !isPrepared(field)) return;
        Accessors.FIELD.setVolatile(field, (Object) null);
        Accessors.OVERRIDE.setVolatile(field, (Object) null);
        Object root = Accessors.ROOT.get(field);
        if (root != null) { Accessors.FIELD.setVolatile(root, (Object) null); Accessors.OVERRIDE.setVolatile(root, (Object) null); }
    }

    private static void purge() {
        Reference<? extends ClassLoader> dead;
        while ((dead = COLLECTED.poll()) != null) PENDING.remove(dead);
    }
}
