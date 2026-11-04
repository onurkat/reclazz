/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

/** Actual successful XML reader loads, associated with their registry by identity. */
public final class SpringXmlResources {
    private SpringXmlResources() { }
    /** Each loaded file by its real path: the path it was loaded from, and the document reader that parsed it. */
    private record Entry(WeakReference<Object> registry, Map<Path, Loaded> paths) { }
    private record Loaded(Path path, WeakReference<Class<?>> documentReader) { }
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
            Loaded loaded = new Loaded(path, new WeakReference<>(documentReaderClass(reader)));
            synchronized (entries) {
                entries.removeIf(e -> e.registry().get() == null);
                for (Entry entry : entries) {
                    if (entry.registry().get() == registry) {
                        entry.paths().put(canonical(path), loaded);
                        return;
                    }
                }
                Map<Path, Loaded> paths = new HashMap<>();
                paths.put(canonical(path), loaded);
                entries.add(new Entry(new WeakReference<>(registry), paths));
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
        Path normalized = canonical(path);
        List<Object> result = new ArrayList<>();
        for (Object context : contexts) {
            Object factory = activeFactory(context);
            if (factory == null) continue;
            synchronized (entries) {
                for (Entry entry : entries) {
                    Object registry = entry.registry().get();
                    if ((registry == context || registry == factory) && entry.paths().containsKey(normalized)) {
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
                    if (registry == context || registry == factory)
                        entry.paths().values().forEach(loaded -> result.add(loaded.path()));
                }
            }
        }
        return result;
    }

    /**
     * The document reader the context parsed this file with, or null for
     * Spring's default. SAP Commerce reads its web contexts with its own, which
     * makes every top-level bean lazy and ignores the tenant scope; a file
     * parsed again without it differs from the live definitions in both.
     */
    public static Class<?> documentReader(Path path, Object context) {
        Path normalized = canonical(path);
        Object factory = activeFactory(context);
        synchronized (entries) {
            for (Entry entry : entries) {
                Object registry = entry.registry().get();
                if (registry == null || (registry != context && registry != factory)) continue;
                Loaded loaded = entry.paths().get(normalized);
                if (loaded != null) return loaded.documentReader().get();
            }
        }
        return null;
    }

    /**
     * One name for a file however it was reached. A context records the path
     * its classloader found, and SAP Commerce resolves a symlinked extension to
     * its target, while the watcher reports the path under {@code bin/custom}.
     */
    public static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException | SecurityException unresolved) {
            return absolute;
        }
    }

    private static Class<?> documentReaderClass(Object reader) {
        for (Class<?> c = reader.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("documentReaderClass");
                field.setAccessible(true);
                Class<?> type = (Class<?>) field.get(reader);
                return type == null || type.getName().equals(
                        "org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader") ? null : type;
            } catch (NoSuchFieldException notHere) {
                // Declared on XmlBeanDefinitionReader, further up.
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
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
