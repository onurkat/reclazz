/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live library objects whose caches a reload must clear (R35: Gson, SpEL property accessors, EL bean resolvers), registered from
 * their constructors and held weakly so their class loaders can go.
 * Bootstrap class: JDK dependencies only.
 */
public final class TrackedInstances {
    private TrackedInstances() { }

    private static final ConcurrentHashMap<String, Kind> KINDS = new ConcurrentHashMap<>();

    /** Called from the constructors {@code CacheOwnerTransformer} instruments. */
    public static void track(Object instance, String kind) { KINDS.computeIfAbsent(kind, k -> new Kind()).track(instance); }

    private static final java.util.Map<Object, Object> BASELINES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Records what a tracked cache held right after its owner configured it (SAP Commerce OCC: the field-set levels from
     * XML), as a copy of a {@code Map<?, Map<?, ?>>}, so a reload can drop only what was computed later.
     */
    public static void baseline(Object instance, Object value) {
        if (instance == null || !(value instanceof java.util.Map<?, ?> map)) return;
        java.util.Map<Object, Object> copy = new java.util.HashMap<>();
        synchronized (map) {
            for (var entry : map.entrySet())
                copy.put(entry.getKey(), entry.getValue() instanceof java.util.Map<?, ?> inner ? new java.util.HashMap<>(inner) : entry.getValue());
        }
        BASELINES.put(instance, copy);
    }

    public static Object baselineOf(Object instance) { return BASELINES.get(instance); }

    public static List<Object> live(String kind) {
        Kind found = KINDS.get(kind);
        return found == null ? new ArrayList<>() : found.live();
    }

    private static final class Kind {
        private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        private final ConcurrentHashMap<Identity, Boolean> registered = new ConcurrentHashMap<>();

        private final class Identity extends WeakReference<Object> {
            final int hash;
            Identity(Object target) { super(target, collected); hash = System.identityHashCode(target); }
            @Override public int hashCode() { return hash; }
            // Identity of the reference itself: a chained constructor may register one object twice, which only
            // means its cache is cleared twice.
        }

        void track(Object instance) {
            if (instance == null) return;
            drain();
            registered.put(new Identity(instance), Boolean.TRUE);
        }

        List<Object> live() {
            drain();
            List<Object> result = new ArrayList<>();
            for (Identity key : registered.keySet()) { Object value = key.get(); if (value != null) result.add(value); }
            return result;
        }

        private void drain() {
            Object dead;
            while ((dead = collected.poll()) != null) registered.remove(dead);
        }
    }
}
