/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Local process resources referenced by existing singletons, never a filename guess. */
public final class SapProcessResources {
    private static final String RESOURCE = "de.hybris.platform.processengine.definition.ProcessDefinitionResource";
    private SapProcessResources() { }

    public static boolean isRegistered(Path file) {
        return registeredFiles().contains(canonical(file));
    }

    /** Physical identity also before file-first/bean-first creation and after deletion. */
    public static Path canonical(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (java.io.IOException | SecurityException missing) {
            Path parent = absolute.getParent();
            return parent == null ? absolute : canonical(parent).resolve(absolute.getFileName());
        }
    }

    /** No retained context/classloader, lazy bean creation, parsing or remote URL access. */
    public static Set<Path> registeredFiles() {
        Set<Path> files = new HashSet<>();
        for (Registration registration : registrations()) files.add(registration.path());
        return files;
    }

    // A mutation invalidates peers of the same concrete registry class too. ClassValue
    // releases the counter with its class and never calls application code from the Spring hook.
    private static final ClassValue<AtomicLong> revisions = new ClassValue<>() {
        @Override protected AtomicLong computeValue(Class<?> type) { return new AtomicLong(); }
    };

    public static void registryChanged(Object factory) {
        revisions.get(factory.getClass()).incrementAndGet();
    }

    static long revision(Object factory) {
        return revisions.get(factory.getClass()).get();
    }

    private record Access(Method singletonNames, Method singleton, Method loader) { }
    private record Factory(WeakReference<Object> identity, long revision, List<String> names) { }
    private record ResourcePath(Object fileKey, Path path) { }

    /** Only active factories' names and resources' paths survive a discovery pass. */
    public static final class Tracker {
        private List<Factory> factories = List.of();
        private final Map<String, ResourcePath> paths = new HashMap<>();
        private static final ClassValue<Method> resourceUrls = new ClassValue<>() {
            @Override protected Method computeValue(Class<?> type) {
                try { return type.getMethod("getURL"); }
                catch (NoSuchMethodException unsupported) { return null; }
            }
        };
        private static final ClassValue<Access> factoryMethods = new ClassValue<>() {
            @Override protected Access computeValue(Class<?> type) {
                try {
                    return new Access(type.getMethod("getSingletonNames"), type.getMethod("getSingleton", String.class),
                            type.getMethod("getBeanClassLoader"));
                } catch (NoSuchMethodException unsupported) { return null; }
            }
        };
        private static final ClassValue<Method> resourceGetters = new ClassValue<>() {
            @Override protected Method computeValue(Class<?> type) {
                try { return type.getMethod("getResource"); }
                catch (NoSuchMethodException unsupported) { return null; }
            }
        };

        public Set<Path> files() {
            Set<Path> files = new HashSet<>();
            Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Factory> nextFactories = new ArrayList<>();
            Set<String> activeUrls = new HashSet<>();
            for (Object context : ApplicationContextHolder.getAllContexts()) {
                try {
                    Object factory = SpringXmlResources.activeFactory(context);
                    if (factory == null || !active.add(factory)) continue;
                    Access access = factoryMethods.get(factory.getClass());
                    if (access == null) continue;
                    ClassLoader loader = (ClassLoader) access.loader().invoke(factory);
                    Class<?> type = Class.forName(RESOURCE, false, loader);
                    Method getResource = resourceGetters.get(type);
                    if (getResource == null) continue;
                    long revision = revision(factory);
                    Factory cached = null;
                    for (Factory known : factories) {
                        if (known.identity().get() == factory) { cached = known; break; }
                    }
                    if (cached == null || revision == 0 || cached.revision() != revision) {
                        List<String> names = new ArrayList<>();
                        for (String name : (String[]) access.singletonNames().invoke(factory)) {
                            if (type.isInstance(access.singleton().invoke(factory, name))) names.add(name);
                        }
                        cached = new Factory(new WeakReference<>(factory), revision, List.copyOf(names));
                    }
                    nextFactories.add(cached);
                    for (String name : cached.names()) {
                        try {
                            Object bean = access.singleton().invoke(factory, name);
                            if (!type.isInstance(bean)) continue;
                            Object resource = getResource.invoke(bean);
                            Path path = path(resource, activeUrls);
                            if (path != null) files.add(path);
                        } catch (Exception | LinkageError unavailable) {
                            // Registration replacement or an unsupported resource affects this entry only.
                        }
                    }
                } catch (Exception | LinkageError unavailable) {
                    // A closed context or non-SAP factory grants no ownership.
                }
            }
            factories = nextFactories;
            paths.keySet().retainAll(activeUrls);
            return files;
        }

        private Path path(Object resource, Set<String> activeUrls) throws Exception {
            if (resource == null) return null;
            Method method = resourceUrls.get(resource.getClass());
            if (method == null) return null;
            URL url = (URL) method.invoke(resource);
            if (!"file".equals(url.getProtocol())) return null;
            String text = url.toExternalForm();
            Path lexical = Path.of(url.toURI());
            activeUrls.add(text);
            Object key;
            try {
                key = Files.readAttributes(lexical, BasicFileAttributes.class).fileKey();
            } catch (java.io.IOException missing) { key = null; }
            ResourcePath cached = paths.get(text);
            if (key != null && cached != null && key.equals(cached.fileKey())) return cached.path();
            Path physical = canonical(lexical);
            if (physical.getFileName() == null || !physical.getFileName().toString().endsWith(".xml")) return null;
            paths.put(text, new ResourcePath(key, physical));
            return physical;
        }
    }

    /** Objects live only for this discovery call; the scanner retains paths alone. */
    public record Registration(Object context, Object beanFactory, String beanName,
                               Object bean, Object resource, Path path) { }

    public static List<Registration> registrations() {
        List<Registration> registrations = new ArrayList<>();
        for (Object context : ApplicationContextHolder.getAllContexts()) {
            try {
                if (!Boolean.TRUE.equals(context.getClass().getMethod("isActive").invoke(context))) continue;
                Object factory = context.getClass().getMethod("getBeanFactory").invoke(context);
                ClassLoader loader = (ClassLoader) factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
                Class<?> resourceType = Class.forName(RESOURCE, false, loader);
                var getSingleton = factory.getClass().getMethod("getSingleton", String.class);
                var getResource = resourceType.getMethod("getResource");
                for (String name : (String[]) factory.getClass().getMethod("getSingletonNames").invoke(factory)) {
                    try {
                        Object bean = getSingleton.invoke(factory, name);
                        if (!resourceType.isInstance(bean)) continue;
                        Object resource = getResource.invoke(bean);
                        if (resource == null) continue;
                        URL url = (URL) resource.getClass().getMethod("getURL").invoke(resource);
                        if (!"file".equals(url.getProtocol())) continue;
                        Path path = canonical(Path.of(url.toURI()));
                        if (path.getFileName() != null && path.getFileName().toString().endsWith(".xml"))
                            registrations.add(new Registration(context, factory, name, bean, resource, path));
                    } catch (Exception | LinkageError unavailable) {
                        // One unsupported registration must not hide the other known resources.
                    }
                }
            } catch (Exception | LinkageError unavailable) {
                // A closed/non-SAP context grants no process-resource ownership.
            }
        }
        return List.copyOf(registrations);
    }
}
