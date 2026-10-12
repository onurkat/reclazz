/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Exact native MVC singleton identities, weak on both sides. Pure JDK bootstrap code. */
public final class MvcSingletonBridge {
    private static final ReferenceQueue<Object> dead = new ReferenceQueue<>();
    private static final Map<Key, WeakReference<Object>> replacements = new HashMap<>();
    private static final Map<Key, Integer> hooks = new HashMap<>();
    private static int bootstrapHooks;

    private MvcSingletonBridge() { }

    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object value, ReferenceQueue<Object> queue) {
            super(value, queue); hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object value = get();
            return other instanceof Key key && value != null && value == key.get();
        }
    }

    private static void clean() {
        Key key;
        while ((key = (Key) dead.poll()) != null) {
            replacements.remove(key); hooks.remove(key);
        }
    }

    /** Transformer receipt: bit1 chain insertion, bit2 servlet mapping reads. */
    public static synchronized void installed(ClassLoader loader, int flag) {
        clean();
        if (loader == null) bootstrapHooks |= flag;
        else {
            Key key = new Key(loader, dead);
            hooks.put(key, hooks.getOrDefault(key, 0) | flag);
        }
    }

    public static synchronized boolean available(ClassLoader loader, int flag) {
        clean();
        int flags = loader == null ? bootstrapHooks : hooks.getOrDefault(new Key(loader, null), 0);
        return (flags & flag) == flag;
    }

    public static synchronized Object current(Object original) {
        if (original == null) return null;
        clean();
        WeakReference<Object> ref = replacements.get(new Key(original, null));
        Object fresh = ref == null ? null : ref.get();
        return fresh == null ? original : fresh;
    }

    /** Collapse older redirects while all old/current identities are still held by the snapshot. */
    public static synchronized void publish(IdentityHashMap<Object, Object> pairs) {
        clean();
        IdentityHashMap<Object, Object> accepted = new IdentityHashMap<>();
        for (var pair : pairs.entrySet()) {
            Object old = pair.getKey(), fresh = pair.getValue();
            if (old == null || fresh == null || old == fresh || old.getClass() != fresh.getClass()) continue;
            Object end = fresh;
            var visited = new IdentityHashMap<Object, Boolean>();
            visited.put(old, true);
            while (end != null && pairs.containsKey(end) && visited.put(end, true) == null) end = pairs.get(end);
            if (end == null || visited.containsKey(end) || old.getClass() != end.getClass()) continue;
            accepted.put(old, end);
        }
        for (var entry : replacements.entrySet()) {
            Object previous = entry.getValue().get();
            Object next = accepted.get(previous);
            if (next != null) entry.setValue(new WeakReference<>(next));
        }
        for (var pair : accepted.entrySet()) replacements.put(new Key(pair.getKey(), dead), new WeakReference<>(pair.getValue()));
    }

    /** Copy only when an entry changes; preserve order and the native list itself. */
    public static synchronized List<?> mappings(List<?> original) {
        if (original == null) return null;
        List<Object> changed = null;
        for (int i = 0; i < original.size(); i++) {
            Object old = original.get(i), fresh = current(old);
            if (old != fresh && changed == null) changed = new ArrayList<>(original);
            if (changed != null) changed.set(i, fresh);
        }
        return changed == null ? original : changed;
    }

    /** The bootstrap registry outlives test contexts. */
    public static synchronized void clearForTests() {
        replacements.clear(); hooks.clear(); bootstrapHooks = 0; clean();
    }
}
