/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/** Observed synchronous Spring cache dependencies. No application callback runs under LOCK. */
public final class CacheDependencyLedger {
    private static final Object LOCK = new Object();
    private static final int MAX_CACHES = 512, MAX_LINKS = 8192;
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final List<Node> NODES = new ArrayList<>();
    private static volatile boolean active, partial = true, overflowed;
    private static volatile Consumer<String> reporter;
    private static int frames, mutationDepth;
    private static long generation;

    private CacheDependencyLedger() { }
    private static <T> Set<T> identities() { return Collections.newSetFromMap(new IdentityHashMap<>()); }
    private static final class Frame {
        final Frame parent;
        final long generation;
        final boolean duringMutation;
        final Set<Class<?>> classes = identities();
        final Set<Object> caches = identities(), children = identities(), pending = identities();
        Frame(Frame parent, long generation, boolean duringMutation) {
            this.parent = parent; this.generation = generation; this.duringMutation = duringMutation;
        }
    }
    private static final class Node {
        final WeakReference<Object> cache;
        final Map<Class<?>, Boolean> classes = new WeakHashMap<>();
        final Set<Node> children = identities();
        Node(Object cache) { this.cache = new WeakReference<>(cache); }
    }
    private static final ClassValue<Method[]> METHODS = new ClassValue<>() {
        @Override protected Method[] computeValue(Class<?> type) {
            try {
                // Use the public interface even for a non-public Cache implementation.
                Class<?> api = cacheApi(type);
                return new Method[] { api.getMethod("getName"), api.getMethod("clear") };
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };
    private static Class<?> cacheApi(Class<?> type) {
        for (Class<?> it : type.getInterfaces()) {
            if (it.getName().equals("org.springframework.cache.Cache")) return it;
            Class<?> found = cacheApi(it);
            if (found != it) return found;
        }
        if (type.getSuperclass() != null) {
            Class<?> found = cacheApi(type.getSuperclass());
            if (found != type.getSuperclass()) return found;
        }
        return type;
    }
    public static void reporter(Consumer<String> sink) { reporter = sink; }
    private static void report(String message) {
        try { Consumer<String> sink = reporter; if (sink != null) sink.accept(message); }
        catch (Throwable ignored) { /* Instrumentation must preserve the application's result. */ }
    }
    /** Called once before registering the Spring transformer. Attach history is always partial. */
    public static void configure(boolean startup) { synchronized (LOCK) { partial = !startup; } }
    public static void partialCoverage() { partial = true; }
    public static boolean completeCoverage() { return !partial && !overflowed; }
    private static void overflow() { overflowed = true; partial = true; }

    public static void open() {
        synchronized (LOCK) {
            CURRENT.set(new Frame(CURRENT.get(), generation, mutationDepth != 0));
            frames++; active = true;
        }
    }
    public static void hit(Class<?> type) {
        if (!active) return;
        Frame f = CURRENT.get();
        if (f == null || overflowed) return;
        if (f.classes.size() < MAX_LINKS) f.classes.add(type); else overflow();
    }
    public static void cachesInUse(Collection<?> caches) {
        Frame f = CURRENT.get();
        if (f == null) return;
        try {
            for (Object cache : caches) {
                if (cache == null) continue;
                if (f.caches.size() < MAX_CACHES) f.caches.add(cache); else overflow();
            }
        } catch (Throwable failure) { partialCoverage(); report("Cache dependency observation failed"); }
    }
    public static void beginMutation() { synchronized (LOCK) { mutationDepth++; generation++; } }
    public static void endMutation() {
        synchronized (LOCK) {
            if (mutationDepth == 0) throw new IllegalStateException("Unbalanced cache mutation boundary");
            mutationDepth--; generation++;
        }
    }
    public static void close() {
        Frame f = CURRENT.get();
        if (f == null) return;
        List<Object> clear = List.of();
        try {
            synchronized (LOCK) {
                try {
                    clean();
                    if (!overflowed) commit(f);
                    if (f.duringMutation || generation != f.generation) {
                        f.pending.addAll(dependents(f.caches));
                    }
                    if (f.parent != null) {
                        merge(f.parent.classes, f.classes, MAX_LINKS);
                        merge(f.parent.children, f.caches, MAX_CACHES);
                        merge(f.parent.children, f.children, MAX_CACHES);
                        merge(f.parent.pending, f.pending, MAX_CACHES);
                    } else clear = new ArrayList<>(f.pending);
                } finally {
                    if (f.parent == null) CURRENT.remove(); else CURRENT.set(f.parent);
                    active = --frames != 0;
                }
            }
            // Nested execute may still hold an outer sync=true cache's loader lock.
            // Defer its invalidations to the outermost exit, after all loaders return.
            int cleared = 0;
            for (Object cache : clear) if (clearObserved(cache)) cleared++;
            if (!clear.isEmpty()) report("Cache computation crossed a reload; " + cleared + " of "
                    + clear.size() + " observed caches invalidated");
        } catch (Throwable failure) {
            partialCoverage(); report("Cache dependency close failed; application execution continues");
        }
    }
    private static <T> void merge(Set<T> into, Collection<T> from, int cap) {
        for (T value : from) {
            if (into.contains(value)) continue;
            if (into.size() >= cap) { overflow(); return; }
            into.add(value);
        }
    }
    private static void clean() {
        NODES.removeIf(n -> n.cache.get() == null);
        for (Node n : NODES) n.children.removeIf(c -> c.cache.get() == null);
    }
    private static Node node(Object cache) {
        for (Node n : NODES) if (n.cache.get() == cache) return n;
        if (NODES.size() >= MAX_CACHES) { overflow(); return null; }
        Node n = new Node(cache); NODES.add(n); return n;
    }
    private static void commit(Frame f) {
        int links = 0;
        for (Node n : NODES) links += n.classes.size() + n.children.size();
        for (Object cache : f.caches) {
            Node n = node(cache); if (n == null) return;
            for (Class<?> cls : f.classes) {
                if (!n.classes.containsKey(cls)) {
                    if (links++ >= MAX_LINKS) { overflow(); return; }
                    n.classes.put(cls, Boolean.TRUE);
                }
            }
            for (Object child : f.children) {
                Node dependency = node(child); if (dependency == null) return;
                if (dependency != n && !n.children.contains(dependency)) {
                    if (links++ >= MAX_LINKS) { overflow(); return; }
                    n.children.add(dependency);
                }
            }
        }
    }
    private static List<Object> dependents(Collection<?> seeds) {
        Set<Object> result = identities(); result.addAll(seeds);
        boolean changed;
        do {
            changed = false;
            for (Node n : NODES) {
                Object cache = n.cache.get();
                if (cache == null || result.contains(cache)) continue;
                for (Node child : n.children) if (result.contains(child.cache.get())) {
                    result.add(cache); changed = true; break;
                }
            }
        } while (changed);
        return new ArrayList<>(result);
    }
    public static List<Object> cachesDependingOn(Class<?> type) {
        synchronized (LOCK) {
            clean();
            List<Object> seeds = new ArrayList<>();
            for (Node n : NODES) if (n.classes.containsKey(type)) {
                Object cache = n.cache.get(); if (cache != null) seeds.add(cache);
            }
            return dependents(seeds);
        }
    }
    public static boolean clearObserved(Object cache) {
        try { METHODS.get(cache.getClass())[1].invoke(cache); return true; }
        catch (Throwable failure) { report("Observed cache eviction failed; dependency retained for retry"); return false; }
    }
    public static String cacheName(Object cache) {
        try { return String.valueOf(METHODS.get(cache.getClass())[0].invoke(cache)); }
        catch (Throwable failure) { return "<unnamed cache>"; }
    }
    static void resetForTests() {
        synchronized (LOCK) {
            NODES.clear(); CURRENT.remove(); frames = mutationDepth = 0; generation = 0;
            active = overflowed = false; partial = true; reporter = null;
        }
    }
}
