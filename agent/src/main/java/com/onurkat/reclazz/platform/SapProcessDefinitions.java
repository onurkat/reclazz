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
    private static final List<Source> RECEIPTS = new ArrayList<>();

    /** Weak exact ownership; a bean name/path alone never grants publication. */
    public static final class Source {
        private final WeakReference<Object> context, beanFactory, bean, resource;
        private volatile WeakReference<Object> ownerCache = new WeakReference<>(null);
        private final String beanName;
        private volatile boolean retired, admitted;
        private final Path path, springSource;
        private Source(SapProcessResources.Registration registration, Path springSource) {
            context = new WeakReference<>(registration.context());
            beanFactory = new WeakReference<>(registration.beanFactory());
            bean = new WeakReference<>(registration.bean());
            resource = new WeakReference<>(registration.resource());
            beanName = registration.beanName(); path = registration.path(); this.springSource = springSource;
        }
        public Path path() { return path; }
        public Path springSource() { return springSource; }
        public Object context() { return context.get(); }
        public String beanName() { return beanName; }
        public boolean admitted() { return admitted; }
        public Object ownerCache() { return ownerCache.get(); }
        public SapProcessResources.Registration registration() {
            try {
                Object ctx = context.get(), bf = beanFactory.get(), value = bean.get(), r = resource.get();
                if (retired || ctx == null || bf == null || value == null || r == null
                        || !Boolean.TRUE.equals(ctx.getClass().getMethod("isActive").invoke(ctx))
                        || ctx.getClass().getMethod("getBeanFactory").invoke(ctx) != bf
                        || bf.getClass().getMethod("getSingleton", String.class).invoke(bf, beanName) != value
                        || value.getClass().getMethod("getResource").invoke(value) != r) return null;
                URL url = (URL) r.getClass().getMethod("getURL").invoke(r);
                if (!"file".equals(url.getProtocol()) || !SapProcessResources.canonical(Path.of(url.toURI())).equals(path)) return null;
                if (springSource != null && SpringXmlResources.owners(springSource, List.of(ctx)).isEmpty()) return null;
                return new SapProcessResources.Registration(ctx, bf, beanName, value, r, path);
            } catch (Exception | LinkageError unavailable) { return null; }
        }
    }

    /** Called only after an observed Spring save successfully creates a singleton. */
    public static void registered(Path springSource, Object context, String beanName) {
        if (SpringXmlResources.owners(springSource, List.of(context)).isEmpty()) return;
        var matches = SapProcessResources.registrations().stream().filter(r -> r.context() == context
                && r.beanName().equals(beanName) && r.bean().getClass().getName().equals(
                "de.hybris.platform.processengine.definition.ProcessDefinitionResource")).toList();
        if (matches.size() != 1) return;
        synchronized (RECEIPTS) {
            RECEIPTS.removeIf(s -> {
                boolean drop = s.registration() == null || (s.context() == context && s.beanName.equals(beanName));
                if (drop) s.retired = true;
                return drop;
            });
            RECEIPTS.add(new Source(matches.get(0), SpringXmlResources.canonical(springSource)));
        }
    }

    /** Removal in source metadata invalidates a receipt even when Spring keeps the old bean. */
    public static void reconcile(Path springSource, Object context, java.util.Set<String> names) {
        Path canonical = SpringXmlResources.canonical(springSource);
        synchronized (RECEIPTS) {
            RECEIPTS.removeIf(s -> {
                boolean drop = s.registration() == null || (s.context() == context
                        && canonical.equals(s.springSource) && !names.contains(s.beanName));
                if (drop) s.retired = true;
                return drop;
            });
        }
    }
    public static List<Source> receipts() {
        synchronized (RECEIPTS) {
            RECEIPTS.removeIf(s -> {
                if (s.registration() != null) return false;
                s.retired = true; return true;
            });
            return List.copyOf(RECEIPTS);
        }
    }
    /** Called by the optional process handler after its dispatch admission, never by discovery. */
    public static void admitCreation(Path path) {
        for (Source source : receipts()) if (source.path.equals(path)) source.admitted = true;
    }
    public static java.util.Set<Path> registeredFrom(Path springSource) {
        Path canonical = SpringXmlResources.canonical(springSource);
        java.util.Set<Path> result = new java.util.LinkedHashSet<>();
        for (Source s : receipts()) if (canonical.equals(s.springSource)) result.add(s.path);
        return result;
    }
    public static List<Source> associations(Path path) {
        synchronized (OBSERVED) {
            return OBSERVED.stream().filter(o -> o.cache.get() != null && o.provider.get() != null
                    && o.definition.path().equals(path) && o.source != null).map(Observed::source).toList();
        }
    }
    public static Source source(Object cache, Object provider, Object context, Definition definition) {
        synchronized (OBSERVED) {
            return OBSERVED.stream().filter(o -> o.cache.get() == cache && o.provider.get() == provider
                    && o.context.get() == context && o.definition.equals(definition)).map(Observed::source)
                    .filter(Objects::nonNull).findFirst().orElse(null);
        }
    }
    public static void forget(Path path) {
        synchronized (RECEIPTS) { RECEIPTS.removeIf(s -> {
            if (!s.path.equals(path)) return false;
            s.retired = true; return true;
        }); }
        synchronized (OBSERVED) { OBSERVED.removeIf(o -> o.definition.path().equals(path)); }
    }
    /** A failed owner must not retire another tenant's proof for the same file. */
    public static void forget(Path path, Object ownerCache) {
        if (ownerCache == null) return;
        synchronized (RECEIPTS) { RECEIPTS.removeIf(s -> {
            if (!s.path.equals(path) || s.ownerCache() != ownerCache) return false;
            s.retired = true; return true;
        }); }
        synchronized (OBSERVED) { OBSERVED.removeIf(o -> o.cache.get() == ownerCache && o.definition.path().equals(path)); }
    }
    /** Bind only after the handler resolves the receipt's exact native owner. */
    public static boolean bindOwner(Source source, Object cache) {
        synchronized (source) {
            Object before = source.ownerCache();
            if (source.registration() == null || (before != null && before != cache)) return false;
            source.ownerCache = new WeakReference<>(cache); return true;
        }
    }
    public static void associate(Object cache, Object provider, Source source, Definition definition) {
        Object context = source.context();
        if (!bindOwner(source, cache)) return;
        synchronized (OBSERVED) {
            OBSERVED.removeIf(o -> o.cache.get() == cache && o.provider.get() == provider
                    && o.context.get() == context && o.definition.path().equals(definition.path()));
            OBSERVED.add(new Observed(new WeakReference<>(cache), new WeakReference<>(provider),
                    new WeakReference<>(context), definition, source));
        }
    }
    private static Source initialSource(Object context, Definition definition) {
        var matches = SapProcessResources.registrations().stream().filter(r -> r.context() == context
                && r.path().equals(definition.path())).toList();
        if (matches.size() != 1) return null;
        var registration = matches.get(0);
        for (Source receipt : receipts()) {
            var saved = receipt.registration();
            if (saved != null && saved.context() == context && saved.bean() == registration.bean()
                    && saved.resource() == registration.resource()) return receipt;
        }
        return new Source(registration, null);
    }
    private SapProcessDefinitions() { }

    public record Definition(Path path, String name, String version) {
        public Definition { path = SapProcessResources.canonical(path); }
    }
    private record Observed(WeakReference<Object> cache, WeakReference<Object> provider,
                            WeakReference<Object> context, Definition definition, Source source) { }
    private static final class Load {
        final Object cache;
        final Deque<Object> providers = new ArrayDeque<>();
        final List<Definition> definitions = new ArrayList<>();
        final List<Object> resources = new ArrayList<>();
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
            if (name != null && version != null) {
                load.definitions.add(new Definition(path, name, version)); load.resources.add(resource);
            }
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
                for (int i = 0; i < load.definitions.size(); i++) {
                    Definition definition = load.definitions.get(i);
                    Source source = initialSource(context, definition);
                    if (source != null && source.resource.get() != load.resources.get(i)) source = null;
                    if (source != null && !bindOwner(source, load.cache)) source = null;
                    OBSERVED.add(new Observed(new WeakReference<>(load.cache), new WeakReference<>(provider),
                            new WeakReference<>(context), definition, source));
                }
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
            Source source = before == null ? null : source(cache, provider, context, before);
            OBSERVED.removeIf(o -> o.cache.get() == cache && o.provider.get() == provider
                    && o.context.get() == context && Objects.equals(o.definition, before));
            if (after != null) OBSERVED.add(new Observed(new WeakReference<>(cache),
                    new WeakReference<>(provider), new WeakReference<>(context), after,
                    source == null ? initialSource(context, after) : source));
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
