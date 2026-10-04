/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

/** Actual successful XML reader loads, associated with their registry by identity. */
public final class SpringXmlResources {
    private SpringXmlResources() { }
    private record Entry(WeakReference<Object> registry, Set<Path> paths) { }
    private static final List<Entry> entries = new ArrayList<>();

    /** Called after a successful doLoadBeanDefinitions return, including nested imports. */
    public static void record(Object reader, Object resource) {
        SpringXmlAliases.complete(reader, resource);
        try {
            Object registry = reader.getClass().getMethod("getRegistry").invoke(reader);
            URL url = (URL) resource.getClass().getMethod("getURL").invoke(resource);
            // Never open a URL or claim packed/remote resources are editable local files.
            if (registry == null || !"file".equals(url.getProtocol())) return;
            Path path = Path.of(url.toURI()).toAbsolutePath().normalize();
            if (!path.getFileName().toString().endsWith(".xml")) return;
            synchronized (entries) {
                entries.removeIf(e -> e.registry().get() == null);
                for (Entry entry : entries) {
                    if (entry.registry().get() == registry) {
                        entry.paths().add(path);
                        return;
                    }
                }
                entries.add(new Entry(new WeakReference<>(registry), new HashSet<>(Set.of(path))));
            }
        } catch (Throwable ignored) {
            // Observation must never turn a successful application parse into a failure.
        }
    }

    public static boolean isLoaded(Path path) {
        return !owners(path, ApplicationContextHolder.getAllContexts()).isEmpty();
    }

    /** Inactive contexts and throwaway parser factories never confer live ownership. */
    public static List<Object> owners(Path path, List<Object> contexts) {
        Path normalized = path.toAbsolutePath().normalize();
        List<Object> result = new ArrayList<>();
        for (Object context : contexts) {
            Object factory = activeFactory(context);
            if (factory == null) continue;
            synchronized (entries) {
                for (Entry entry : entries) {
                    Object registry = entry.registry().get();
                    if ((registry == context || registry == factory) && entry.paths().contains(normalized)) {
                        if (!result.contains(context)) result.add(context);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** Snapshot for the watcher; no context, factory or classloader is retained by it. */
    public static Set<Path> loadedFiles() {
        Set<Path> result = new HashSet<>();
        for (Object context : ApplicationContextHolder.getAllContexts()) {
            Object factory = activeFactory(context);
            if (factory == null) continue;
            synchronized (entries) {
                entries.removeIf(e -> e.registry().get() == null);
                for (Entry entry : entries) {
                    Object registry = entry.registry().get();
                    if (registry == context || registry == factory) result.addAll(entry.paths());
                }
            }
        }
        return result;
    }

    private static Object activeFactory(Object context) {
        try {
            if (!Boolean.TRUE.equals(context.getClass().getMethod("isActive").invoke(context))) return null;
            return context.getClass().getMethod("getBeanFactory").invoke(context);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }
}
