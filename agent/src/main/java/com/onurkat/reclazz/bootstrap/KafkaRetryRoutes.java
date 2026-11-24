/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Published route snapshots. The owning Spring context retains its table while it is live. */
public final class KafkaRetryRoutes {
    private static volatile List<WeakReference<KafkaRetryRoutes>> tables = List.of();
    private static final ThreadLocal<Object> probe = new ThreadLocal<>();
    private final Object resolver;
    private volatile Map<Object,Object> published;
    private final Map<Object,Object> managed = new LinkedHashMap<>();
    private int contributions;
    private boolean masksOriginal;

    public KafkaRetryRoutes(Object resolver, Map<?,?> baseline) {
        this.resolver = resolver;
        synchronized (baseline) {
            this.published = snapshot(baseline);
            synchronized (KafkaRetryRoutes.class) {
                var next = new ArrayList<>(tables);
                next.removeIf(ref -> ref.get() == null);
                for (var ref : next) {
                    var other = ref.get();
                    if (other != null && other.resolver == resolver) throw new IllegalStateException("Kafka route table already owned");
                }
                next.add(new WeakReference<>(this));
                tables = List.copyOf(next);
            }
        }
    }

    /** Called at the native GETFIELD, including on a preloaded, retransformed resolver. */
    public static Map<?,?> view(Object resolver, Map<?,?> original) {
        if (probe.get() == resolver) probe.set(Boolean.TRUE);
        for (var ref : tables) {
            KafkaRetryRoutes table = ref.get();
            if (table != null && table.resolver == resolver) return table.published;
        }
        return original;
    }

    private static Map<Object,Object> snapshot(Map<?,?> source) {
        var copy = new LinkedHashMap<Object,Object>();
        source.forEach((key, value) -> copy.put(key, value instanceof Map<?,?> nested ? Map.copyOf(nested) : value));
        return Map.copyOf(copy);
    }

    // Both measured native writers call putAll under their original map lock.
    // A resolver that has not received ContextRefreshedEvent may still accept native additions.
    public static void nativeWrite(Map<Object,Object> destination, Map<?,?> incoming, Object resolver, String listenerId) {
        for (var reference : tables) {
            var table = reference.get();
            if (table != null && table.resolver == resolver) {
                table.write(destination, incoming, listenerId);
                return;
            }
        }
        destination.putAll(incoming);
    }

    private synchronized void write(Map<Object,Object> destination, Map<?,?> incoming, String id) {
        if (id != null && managed.containsKey(id)) throw new IllegalStateException("Kafka retry route already owned: " + id);
        for (Object topic : incoming.keySet()) {
            if (managed.containsKey(topic) || (id != null && managed.values().stream().anyMatch(
                    value -> value instanceof Map<?,?> nested && nested.containsKey(topic))))
                throw new IllegalStateException("Kafka retry route already owned: " + topic);
        }
        var next = new LinkedHashMap<>(published);
        if (id == null) next.putAll(incoming);
        else {
            var nested = new LinkedHashMap<Object,Object>();
            if (next.get(id) instanceof Map<?,?> prior) nested.putAll(prior);
            nested.putAll(incoming);
            next.put(id, Map.copyOf(nested));
        }
        destination.putAll(incoming);
        published = Map.copyOf(next);
    }

    // Kafka 3 normally inserts an empty map before validating the incoming routes.
    // While a table is managed, expose a new listener ID only after its write succeeds.
    public static Object destinationFor(Map<Object,Object> original, Object id, java.util.function.Function<Object,Object> mapping,
                                        Object resolver) {
        for (var reference : tables) {
            var table = reference.get();
            if (table != null && table.resolver == resolver) {
                Object existing = original.get(id);
                return existing == null ? mapping.apply(id) : existing;
            }
        }
        return original.computeIfAbsent(id, mapping);
    }

    public static void nativeWriteForId(Map<Object,Object> destination, Map<?,?> incoming, Object resolver, String id,
                                        Map<Object,Object> original) {
        nativeWrite(destination, incoming, resolver, id);
        original.putIfAbsent(id, destination);
    }

    public boolean owns(Object value) { return resolver == value; }

    public static void beginProbe(Object resolver) { probe.set(resolver); }
    public static boolean endProbe() {
        boolean observed = probe.get() == Boolean.TRUE;
        probe.remove();
        return observed;
    }

    public synchronized void add(Map<?,?> entries) {
        var next = new LinkedHashMap<>(published);
        for (var entry : entries.entrySet()) {
            // Kafka 3 keys by listener ID. Managed groups conservatively require disjoint broker topics.
            if (entry.getValue() instanceof Map<?,?> incoming) {
                for (Object prior : published.values()) {
                    if (!(prior instanceof Map<?,?> existing)) continue;
                    for (Object topic : incoming.keySet()) if (existing.containsKey(topic))
                        throw new IllegalStateException("Kafka retry route already belongs to another listener: " + topic);
                }
            }
            if (next.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new IllegalStateException("Kafka retry route already belongs to another listener: " + entry.getKey());
        }
        published = Map.copyOf(next);
        managed.putAll(entries);
        contributions++;
    }

    public synchronized boolean claimOriginal(Map<?,?> entries) {
        for (var entry : entries.entrySet()) {
            if (managed.containsKey(entry.getKey())) return false;
            Object value = published.get(entry.getKey());
            if (value != entry.getValue()) {
                if (!(value instanceof Map<?,?> inner) || !(entry.getValue() instanceof Map<?,?> incoming)
                        || inner.size() != incoming.size() || incoming.entrySet().stream()
                                .anyMatch(topic -> inner.get(topic.getKey()) != topic.getValue())) return false;
            }
            if (entry.getValue() instanceof Map<?,?> group) {
                for (var other : published.entrySet()) {
                    if (other.getKey().equals(entry.getKey()) || !(other.getValue() instanceof Map<?,?> topics)) continue;
                    for (Object topic : group.keySet()) if (topics.containsKey(topic)) return false;
                }
            }
        }
        managed.putAll(entries);
        contributions++;
        return true;
    }

    public synchronized void remove(Map<?,?> entries, boolean original) {
        var next = new LinkedHashMap<>(published);
        for (var entry : entries.entrySet()) {
            if (!managed.containsKey(entry.getKey()) || managed.get(entry.getKey()) != entry.getValue())
                throw new IllegalStateException("Kafka retry route ownership changed: " + entry.getKey());
            next.remove(entry.getKey());
        }
        published = Map.copyOf(next);
        for (Object key : entries.keySet()) managed.remove(key);
        contributions--;
        masksOriginal |= original;
    }

    /** Caller holds the context ownership lock across detach and singleton removal. */
    public synchronized boolean detachIfUnused() {
        if (contributions != 0 || masksOriginal) return false;
        synchronized (KafkaRetryRoutes.class) {
            var active = new ArrayList<>(tables);
            active.removeIf(ref -> ref.get() == null || ref.get() == this);
            tables = List.copyOf(active);
        }
        return true;
    }
}
