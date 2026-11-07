/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.lang.ref.WeakReference;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Observations of native initial loads, never inferred from edited file contents. */
public final class SapProcessDefinitions {
    private static final ThreadLocal<Deque<Load>> LOADS = new ThreadLocal<>();
    private static final List<Observed> OBSERVED = new ArrayList<>();
    private SapProcessDefinitions() { }

    public record Definition(Path path, String name, String version) { }
    private record Observed(WeakReference<Object> cache, WeakReference<Object> provider,
                            WeakReference<Object> context, Definition definition) { }
    private static final class Load {
        final Object cache;
        final Deque<Object> providers = new ArrayDeque<>();
        final List<Definition> definitions = new ArrayList<>();
        boolean failed;
        Load(Object cache) { this.cache = cache; }
    }

    public static void enterCache(Object cache) {
        Deque<Load> stack = LOADS.get();
        if (stack == null) { stack = new ArrayDeque<>(); LOADS.set(stack); }
        stack.push(new Load(cache));
    }

    public static void enterProvider(Object provider) {
        Deque<Load> stack = LOADS.get();
        if (stack != null && !stack.isEmpty()) stack.peek().providers.push(provider);
    }

    public static void exitProvider(boolean success) {
        Deque<Load> stack = LOADS.get();
        if (stack == null || stack.isEmpty()) return;
        Load load = stack.peek();
        if (!success) load.failed = true;
        if (!load.providers.isEmpty()) load.providers.pop();
    }

    public static void parsed(Object resource, Object definition) {
        Deque<Load> stack = LOADS.get();
        if (stack == null || stack.isEmpty()) return;
        Load load = stack.peek();
        if (load.providers.isEmpty()) return;
        try {
            Object provider = field(load.cache, "processDefinitionsProvider");
            if (load.providers.peek() != provider) return;
            URL url = (URL) resource.getClass().getMethod("getURL").invoke(resource);
            if (!"file".equals(url.getProtocol())) return;
            Path path = Path.of(url.toURI()).toAbsolutePath().normalize();
            String name = text(definition, "getName");
            String version = text(definition, "getVersion");
            if (name != null && version != null) load.definitions.add(new Definition(path, name, version));
        } catch (Throwable unavailable) {
            load.failed = true;
            com.onurkat.reclazz.ui.StatusReporter.detail("SAP process source observation unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    public static void exitCache(boolean success) {
        Deque<Load> stack = LOADS.get();
        if (stack == null || stack.isEmpty()) return;
        Load load = stack.pop();
        if (stack.isEmpty()) LOADS.remove();
        try {
            if (!success || load.failed) return;
            Object provider = field(load.cache, "processDefinitionsProvider");
            Object context = field(provider, "applicationContext");
            if (context == null) return;
            synchronized (OBSERVED) {
                discard(load.cache);
                for (Definition definition : load.definitions) OBSERVED.add(new Observed(
                        new WeakReference<>(load.cache), new WeakReference<>(provider),
                        new WeakReference<>(context), definition));
            }
        } catch (Throwable unavailable) {
            com.onurkat.reclazz.ui.StatusReporter.detail("SAP process source association unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    public static List<Definition> observed(Object cache, Object provider, Object context) {
        synchronized (OBSERVED) {
            OBSERVED.removeIf(o -> o.cache.get() == null || o.provider.get() == null || o.context.get() == null);
            return OBSERVED.stream().filter(o -> o.cache.get() == cache && o.provider.get() == provider
                    && o.context.get() == context).map(Observed::definition).toList();
        }
    }

    public static void replace(Object cache, Object provider, Object context, Definition before, Definition after) {
        synchronized (OBSERVED) {
            OBSERVED.removeIf(o -> o.cache.get() == cache && o.provider.get() == provider
                    && o.context.get() == context && Objects.equals(o.definition, before));
            if (after != null) OBSERVED.add(new Observed(new WeakReference<>(cache),
                    new WeakReference<>(provider), new WeakReference<>(context), after));
        }
    }

    public static void discard(Object cache) {
        synchronized (OBSERVED) { OBSERVED.removeIf(o -> o.cache.get() == cache || o.cache.get() == null); }
    }

    /** Read an exact native field without invoking an accessor or initialising its cache. */
    public static Object field(Object instance, String name) throws ReflectiveOperationException {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException absent) { /* Native provider subclasses are used in tests. */ }
        }
        throw new NoSuchFieldException(name);
    }

    /** Resolve only the named signature; unrelated generated model classes need not be loaded. */
    public static String text(Object instance, String method) throws ReflectiveOperationException {
        try {
            Class<?> owner = instance.getClass();
            // The native reader returns package-private XMLProcessDefinition.
            // Resolve its public ProcessDefinition contract while preserving virtual dispatch.
            while (owner != null && !java.lang.reflect.Modifier.isPublic(owner.getModifiers())) owner = owner.getSuperclass();
            if (owner == null) throw new IllegalAccessException("No public native definition contract");
            return (String) MethodHandles.publicLookup().findVirtual(owner, method,
                    MethodType.methodType(String.class)).invoke(instance);
        } catch (ReflectiveOperationException unavailable) { throw unavailable; }
        catch (Throwable failure) { throw new ReflectiveOperationException("Native definition accessor failed", failure); }
    }
}
