/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

/** Alias declarations observed during native XML parsing, never an alias registry mutator. */
public final class SpringXmlAliases {
    private SpringXmlAliases() { }
    private record Entry(WeakReference<Object> owner, Map<Path, Map<String, String>> files) { }
    private static final List<Entry> pending = new ArrayList<>();
    private static final List<Entry> loaded = new ArrayList<>();

    public static synchronized void begin(Object reader, Object resource) {
        try {
            Path path = path(resource);
            if (path != null) entry(pending, reader).files().put(path, new LinkedHashMap<>());
        } catch (Throwable ignored) { /* Observation must not affect application parsing. */ }
    }

    public static void alias(Object context, String name, String alias) {
        try { record(call(context, "getReader"), call(context, "getResource"), name, alias); }
        catch (Throwable ignored) { }
    }

    public static void component(Object context, Object component) {
        try { component(call(context, "getReader"), call(context, "getResource"), component); }
        catch (Throwable ignored) { }
    }

    private static void component(Object reader, Object resource, Object component) throws Exception {
        // Standard BeanComponentDefinition exposes its BeanDefinitionHolder aliases.
        // Other namespace components do not necessarily represent a named bean.
        try {
            String[] aliases = (String[]) call(component, "getAliases");
            if (aliases != null) for (String alias : aliases)
                record(reader, resource, (String) call(component, "getBeanName"), alias);
        } catch (NoSuchMethodException notABeanHolder) { }
    }

    private static synchronized void record(Object reader, Object resource, String name, String alias) throws Exception {
        Path path = path(resource);
        if (path != null) entry(pending, reader).files()
                .computeIfAbsent(path, unused -> new LinkedHashMap<>()).put(alias, name);
    }

    /** Publish only after the native reader successfully returns. Failed parses keep the old baseline. */
    public static synchronized void complete(Object reader, Object resource) {
        try {
            Path path = path(resource);
            Entry staged = find(pending, reader);
            if (path == null || staged == null) return;
            Map<String, String> aliases = staged.files().remove(path);
            if (aliases != null) entry(loaded, call(reader, "getRegistry")).files().put(path, Map.copyOf(aliases));
        } catch (Throwable ignored) { }
    }

    /** Null means no successful observation; an empty map is a known alias-free resource. */
    public static synchronized Map<String, String> snapshot(Object registry, Path path) {
        Entry entry = find(loaded, registry);
        return entry == null ? null : entry.files().get(path.toAbsolutePath().normalize());
    }

    /** Only for agent-owned sandbox readers. Never replaces an application's event listener. */
    public static void observeReader(Object reader, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> source = Class.forName("org.springframework.beans.factory.parsing.SourceExtractor", false, loader);
        Object extractor = Proxy.newProxyInstance(loader, new Class<?>[]{source}, (proxy, method, args) ->
                method.getName().equals("extractSource") ? args[1] : objectMethod(proxy, method.getName(), args));
        reader.getClass().getMethod("setSourceExtractor", source).invoke(reader, extractor);
        Class<?> events = Class.forName("org.springframework.beans.factory.parsing.ReaderEventListener", false, loader);
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{events}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method.getName(), args);
            Object definition = args[0];
            if (method.getName().equals("aliasRegistered"))
                record(reader, call(definition, "getSource"), (String) call(definition, "getBeanName"),
                        (String) call(definition, "getAlias"));
            else if (method.getName().equals("componentRegistered"))
                component(reader, call(definition, "getSource"), definition);
            return null;
        });
        reader.getClass().getMethod("setEventListener", events).invoke(reader, listener);
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Reclazz XML alias observer";
            default -> null;
        };
    }
    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }
    private static Path path(Object resource) throws Exception {
        if (resource == null) return null;
        URL url = (URL) call(resource, "getURL");
        return "file".equals(url.getProtocol()) ? Path.of(url.toURI()).toAbsolutePath().normalize() : null;
    }
    private static Entry find(List<Entry> entries, Object owner) {
        entries.removeIf(entry -> entry.owner().get() == null);
        for (Entry entry : entries) if (entry.owner().get() == owner) return entry;
        return null;
    }
    private static Entry entry(List<Entry> entries, Object owner) {
        Entry found = find(entries, owner);
        if (found != null) return found;
        Entry created = new Entry(new WeakReference<>(owner), new HashMap<>());
        entries.add(created); return created;
    }
}
