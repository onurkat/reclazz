/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Weak construction witnesses shared by isolated application loaders. */
public final class HibernateFactories {
    private HibernateFactories() { }
    private static final List<Entry> factories = new ArrayList<>();
    private static long generation;

    private static final class Entry extends WeakReference<Object> {
        boolean initialized;
        Entry(Object factory) { super(factory); }
    }

    public record Factory(Object instance, boolean initialized) { }
    public record Snapshot(long generation, List<Factory> factories) { }

    public static synchronized void constructing(Object factory) {
        factories.removeIf(entry -> entry.get() == null);
        factories.add(new Entry(factory));
        generation++;
    }

    private static volatile java.util.function.Consumer<Object> observer;
    public static void onInitialized(java.util.function.Consumer<Object> callback) { observer = callback; }

    public static void initialized(Object factory) {
        markInitialized(factory);
        var callback = observer;
        if (callback != null) callback.accept(factory);
    }

    private static synchronized void markInitialized(Object factory) {
        for (Entry entry : factories) {
            if (entry.get() == factory) {
                entry.initialized = true;
                generation++;
                return;
            }
        }
    }

    public static synchronized Snapshot snapshot() {
        List<Factory> live = new ArrayList<>();
        for (Entry entry : factories) {
            Object factory = entry.get();
            if (factory != null) live.add(new Factory(factory, entry.initialized));
        }
        return new Snapshot(generation, List.copyOf(live));
    }

    public static synchronized boolean unchanged(long observedGeneration) {
        return generation == observedGeneration;
    }
}
