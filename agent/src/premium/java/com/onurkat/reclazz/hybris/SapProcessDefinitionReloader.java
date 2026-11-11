/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ext.AdviceOnlyChangeHandler;
import com.onurkat.reclazz.ext.PlatformChangeHandler;
import com.onurkat.reclazz.platform.SapProcessDefinitions;
import com.onurkat.reclazz.platform.SapProcessResources;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

/** Native parse then guarded publication. Discovery and the startup observer never call this. */
public final class SapProcessDefinitionReloader implements PlatformChangeHandler {
    private static final String PREFIX = "de.hybris.platform.processengine.definition.";

    private static final int MAX_REFUSALS = 200;
    private static final List<Refusal> refusals = new ArrayList<>();
    private static long nextRefusal;
    private record Refusal(Path path, WeakReference<Object> context, String reason, String ledgerReason) { }

    @Override public Outcome handle(Change change) {
        Path path = SapProcessResources.canonical(change.path());
        if ("DELETED".equals(change.eventType())) {
            SapProcessDefinitions.forget(path);
            return new AdviceOnlyChangeHandler().handle(change);
        }
        var associations = SapProcessDefinitions.associations(path);
        var receipts = SapProcessDefinitions.receipts();
        var contexts = new java.util.ArrayList<Object>();
        for (var source : associations) {
            Object context = source.context();
            if (context == null) SapProcessDefinitions.forget(path, source.ownerCache());
            else if (contexts.stream().noneMatch(c -> c == context)) contexts.add(context);
        }
        for (var receipt : receipts) if (receipt.path().equals(path) && receipt.context() != null
                && contexts.stream().noneMatch(c -> c == receipt.context())) contexts.add(receipt.context());
        if (contexts.isEmpty()) {
            if (!"MODIFIED".equals(change.eventType())) return new AdviceOnlyChangeHandler().handle(change);
            var registrations = SapProcessResources.registrations().stream().filter(r -> r.path().equals(path)).toList();
            if (registrations.size() != 1) return refuse(path, "registered process resource ownership is ambiguous or unavailable");
            contexts.add(registrations.get(0).context());
        }
        SapProcessDefinitions.admitCreation(path);
        boolean handled = false, advice = false;
        for (Object context : contexts) {
            try {
                var associated = associations.stream().filter(source -> source.context() == context).toList();
                boolean creation = associated.isEmpty() && receipts.stream().anyMatch(r -> r.path().equals(path) && r.context() == context);
                Native n = nativeOwner(context, creation);
                if (n == null) {
                    advice = true;
                    refuse(path, context, "custom process definition factory/cache/reader or native provider owner unavailable");
                    continue;
                }
                Outcome result = (Outcome) SapOwnerScope.execute(context, () -> {
                    if (associated.isEmpty() && receipts.stream().anyMatch(r -> r.path().equals(path) && r.context() == context))
                        return create(path, n, receipts);
                    if (associated.size() > 1) return refuse(path, context, "recorded process resource is ambiguous");
                    SapProcessDefinitions.Source source = associated.isEmpty() ? null : associated.get(0);
                    var owners = SapProcessResources.registrations().stream().filter(r -> r.path().equals(path) && r.context() == context).toList();
                    if (source != null && (source.registration() == null || source.ownerCache() != n.cache)) {
                        SapProcessDefinitions.forget(path, source.ownerCache());
                        return refuse(path, context, "recorded process resource was replaced");
                    }
                    if (owners.size() != 1) return refuse(path, context, "registered process resource ownership is ambiguous or unavailable");
                    var owner = owners.get(0);
                    if (source != null && source.registration().bean() != owner.bean()) {
                        SapProcessDefinitions.forget(path, n.cache);
                        return refuse(path, context, "recorded process resource was replaced");
                    }
                    return edit(path, n, owner, source);
                });
                handled |= result == Outcome.HANDLED;
                advice |= result == Outcome.ADVICE;
            } catch (Exception | LinkageError failure) {
                Throwable cause = failure;
                for (int i = 0; i < 16 && cause.getCause() != null && cause.getCause() != cause; i++) cause = cause.getCause();
                String message = cause.getMessage();
                if (message != null) message = message.replaceAll("(?:file:)?/[^\\s'\"<>]+", "<resource>");
                refuse(path, context, "native process validation failed: " + cause.getClass().getSimpleName()
                        + (message == null ? "" : " " + message));
                advice = true;
            }
        }
        return handled ? Outcome.HANDLED : advice ? Outcome.ADVICE : Outcome.IGNORED;
    }

    private Outcome edit(Path path, Native n, SapProcessResources.Registration owner,
                         SapProcessDefinitions.Source source) throws Exception {
        Object context = n.context, beanFactory = n.beanFactory, factory = n.factory, cache = n.cache, provider = n.provider;
        String factoryName = n.factoryName;
        ClassLoader loader = n.loader;
        ReadWriteLock lock = (ReadWriteLock) SapProcessDefinitions.field(cache, "definitionsLock");
        SapProcessDefinitions.Definition baseline;
        Object active, activeId, reader;
        Class<?> idType;
        lock.readLock().lock();
        try {
            Object raw = SapProcessDefinitions.field(cache, "definitions");
            if (raw == null) {
                StatusReporter.info(path.getFileName() + " saved before SAP loaded process definitions; "
                        + "it will be read when they first load");
                return Outcome.IGNORED;
            }
            var observed = SapProcessDefinitions.observed(cache, provider, context).stream()
                    .filter(d -> d.path().equals(path)).toList();
            if (observed.size() != 1) return refuse(path, context,
                    "this file was loaded before Reclazz could observe it; restart to load it");
            baseline = observed.get(0);
            idType = Class.forName(PREFIX + "ProcessDefinitionId", false, loader);
            activeId = idType.getConstructor(String.class).newInstance(baseline.name());
            active = ((Map<?, ?>) raw).get(activeId);
            if (active == null || !baseline.version().equals(version(active))) {
                SapProcessDefinitions.forget(path, cache);
                return refuse(path, context, "active process definition was replaced elsewhere; startup association discarded");
            }
            reader = SapProcessDefinitions.field(factory, "xmlDefinitionsReader");
            if (reader == null || !reader.getClass().getName().equals(PREFIX + "XMLProcessDefinitionsReader"))
                return refuse(path, context, "custom process definition reader");
        } finally { lock.readLock().unlock(); }
        // Native parsing may resolve application beans. It must not hold a cache lock.
        Class<?> resourceType = Class.forName("org.springframework.core.io.Resource", false, loader);
        var parse = reader.getClass().getMethod("from", resourceType);
        Object candidate = parse.invoke(reader, owner.resource());
        String name = name(candidate);
        if (!baseline.name().equals(name)) return refuse(path, context, "process name changed from " + baseline.name() + " to " + name);
        for (var other : SapProcessDefinitions.observed(cache, provider, context)) {
            if (other.path().equals(path)) continue;
            if (name.equals(other.name()))
                return refuse(path, context, "multiple registered resources define process " + name);
        }
        if (provider == null || provider.getClass().getMethod("getLatestDefinitionIdFor", idType)
                .invoke(provider, activeId) != null)
            return refuse(path, context, "active DB-backed DynamicProcessDefinition for " + name);
        String version = version(candidate);
        if (version == null) return refuse(path, context, "native reader returned no content version");
        lock.writeLock().lock();
        try {
            if (!Boolean.TRUE.equals(context.getClass().getMethod("isActive").invoke(context)))
                return refuse(path, context, "application context closed during parsing");
            Object current = SapProcessDefinitions.field(cache, "definitions");
            if (context.getClass().getMethod("getBeanFactory").invoke(context) != beanFactory
                    || beanFactory.getClass().getMethod("getSingleton", String.class).invoke(beanFactory, factoryName) != factory
                    || SapProcessDefinitions.field(factory, "definitionsCache") != cache
                    || SapProcessDefinitions.field(cache, "processDefinitionsProvider") != provider
                    || SapProcessDefinitions.field(provider, "applicationContext") != context
                    || SapProcessDefinitions.field(factory, "xmlDefinitionsReader") != reader
                    || (source != null && source.registration() == null)
                    || SapProcessResources.registrations().stream().filter(r -> r.context() == context && r.path().equals(path)).count() != 1
                    || current == null || ((Map<?, ?>) current).get(activeId) != active) {
                SapProcessDefinitions.forget(path, cache);
                return refuse(path, context, "process definition ownership changed during validation");
            }
            if (version.equals(version(active))) {
                resolve(path, context);
                StatusReporter.info("SAP process " + name + " unchanged in " + path.getFileName() + " [" + tag(context) + "]");
                return Outcome.IGNORED;
            }
            Class<?> definitionType = Class.forName(PREFIX + "ProcessDefinition", false, loader);
            factory.getClass().getMethod("add", definitionType).invoke(factory, candidate);
            SapProcessDefinitions.replace(cache, provider, context, baseline,
                    new SapProcessDefinitions.Definition(path, name, version));
            resolve(path, context);
            StatusReporter.info("SAP process " + name + " reloaded from " + path.getFileName()
                    + ": processes started from now on use the edited definition; "
                    + "processes already started keep the version they started with. [" + tag(context) + "]");
            return Outcome.HANDLED;
        } finally { lock.writeLock().unlock(); }
    }

    private record Native(Object context, Object beanFactory, String factoryName, Object factory,
                          Object cache, Object provider, Object reader, ClassLoader loader, ReadWriteLock lock) { }
    private record Parsed(SapProcessDefinitions.Source source, Object definition, String name, String version, Object id) { }

    /** Each native owner's complete batch is guarded and published independently. */
    private Outcome create(Path path, Native n, List<SapProcessDefinitions.Source> receipts) throws Exception {
        Object expectedDefinitions;
        n.lock.readLock().lock();
        try {
            expectedDefinitions = SapProcessDefinitions.field(n.cache, "definitions");
            if (expectedDefinitions == null) {
                StatusReporter.info(path.getFileName() + " saved before SAP loaded process definitions; it will be read when they first load");
                return Outcome.IGNORED;
            }
        } finally { n.lock.readLock().unlock(); }
        var registrations = SapProcessResources.registrations().stream().filter(r -> r.context() == n.context).toList();
        var observed = SapProcessDefinitions.observed(n.cache, n.provider, n.context);
        if (observed.stream().anyMatch(d -> d.path().equals(path))) return Outcome.IGNORED;
        var pending = receipts.stream().filter(r -> r.context() == n.context
                && observed.stream().noneMatch(d -> d.path().equals(r.path()))).toList();
        for (var registration : registrations) {
            boolean proven = receipts.stream().anyMatch(r -> same(r.registration(), registration))
                    || observed.stream().anyMatch(d -> {
                        var source = SapProcessDefinitions.source(n.cache, n.provider, n.context, d);
                        return source != null && same(source.registration(), registration);
                    });
            if (!proven) return refuse(path, n.context, "unproven process resource " + registration.path().getFileName());
        }
        Class<?> resourceType = Class.forName("org.springframework.core.io.Resource", false, n.loader);
        Class<?> idType = Class.forName(PREFIX + "ProcessDefinitionId", false, n.loader);
        java.util.ArrayList<Parsed> parsed = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        if (pending.stream().anyMatch(source -> !source.admitted()))
            return refuse(path, n.context, "process resource has not passed process-change admission");
        for (var source : pending) {
            if (!SapProcessDefinitions.bindOwner(source, n.cache)) {
                SapProcessDefinitions.forget(source.path(), source.ownerCache());
                return refuse(path, n.context, "process resource ownership changed before parsing");
            }
            var registration = source.registration();
            if (registration == null) return refuse(path, n.context, "process resource ownership changed before parsing");
            Object definition = n.reader.getClass().getMethod("from", resourceType).invoke(n.reader, registration.resource());
            String name = name(definition), version = version(definition);
            if (name == null || name.isBlank() || version == null) return refuse(path, n.context, "native reader returned no name or version");
            if (!names.add(name) || observed.stream().anyMatch(d -> d.name().equals(name)))
                return refuse(path, n.context, "multiple registered resources define process " + name);
            Object id = idType.getConstructor(String.class).newInstance(name);
            if (n.provider.getClass().getMethod("getLatestDefinitionIdFor", idType).invoke(n.provider, id) != null)
                return refuse(path, n.context, "active DB-backed DynamicProcessDefinition for " + name);
            parsed.add(new Parsed(source, definition, name, version, id));
        }
        n.lock.writeLock().lock();
        try {
            Object raw = SapProcessDefinitions.field(n.cache, "definitions");
            if (raw != expectedDefinitions || !current(n)) {
                parsed.forEach(c -> SapProcessDefinitions.forget(c.source.path(), n.cache));
                return refuse(path, n.context, "process definition ownership changed during validation");
            }
            var currentResources = SapProcessResources.registrations().stream().filter(r -> r.context() == n.context).toList();
            if (currentResources.size() != registrations.size() || registrations.stream().anyMatch(r ->
                    currentResources.stream().noneMatch(c -> same(r,c))))
                return refuse(path, n.context, "process resource inventory changed during validation");
            for (var candidate : parsed) {
                if (candidate.source.registration() == null) {
                    SapProcessDefinitions.forget(candidate.source.path(), n.cache);
                    return refuse(path, n.context, "process resource ownership changed during validation");
                }
                for (Object id : ((Map<?, ?>) raw).keySet()) {
                    Object existingName = SapProcessDefinitions.field(id, "name");
                    if (candidate.name.equals(existingName)) return refuse(path, n.context, "existing active or historical process name " + candidate.name);
                }
            }
            Class<?> definitionType = Class.forName(PREFIX + "ProcessDefinition", false, n.loader);
            for (var candidate : parsed) {
                n.factory.getClass().getMethod("add", definitionType).invoke(n.factory, candidate.definition);
                SapProcessDefinitions.associate(n.cache, n.provider, candidate.source,
                        new SapProcessDefinitions.Definition(candidate.source.path(), candidate.name, candidate.version));
                resolve(candidate.source.path(), n.context);
                StatusReporter.success("SAP process " + candidate.name + " registered in " + tag(n.context) + " from " + candidate.source.path().getFileName()
                        + ": new processes can be started now; restart not required.");
            }
            return parsed.isEmpty() ? Outcome.IGNORED : Outcome.HANDLED;
        } finally { n.lock.writeLock().unlock(); }
    }
    private static boolean same(SapProcessResources.Registration a, SapProcessResources.Registration b) {
        return a != null && b != null && a.context() == b.context() && a.beanFactory() == b.beanFactory()
                && a.beanName().equals(b.beanName()) && a.bean() == b.bean() && a.resource() == b.resource() && a.path().equals(b.path());
    }
    private static Native nativeOwner(Object context) throws ReflectiveOperationException {
        return nativeOwner(context, true);
    }
    private static Native nativeOwner(Object context, boolean creation) throws ReflectiveOperationException {
        if (context == null || !Boolean.TRUE.equals(context.getClass().getMethod("isActive").invoke(context))) return null;
        Object bf = context.getClass().getMethod("getBeanFactory").invoke(context);
        ClassLoader loader = (ClassLoader) bf.getClass().getMethod("getBeanClassLoader").invoke(bf);
        Object factory = null; String factoryName = null;
        Class<?> factoryType = Class.forName(PREFIX + "ProcessDefinitionFactory", false, loader);
        for (String name : (String[]) bf.getClass().getMethod("getSingletonNames").invoke(bf)) {
            Object bean = bf.getClass().getMethod("getSingleton", String.class).invoke(bf,name);
            if (!factoryType.isInstance(bean)) continue;
            if (factory != null && bean != factory) return null;
            factory = bean; factoryName = name;
        }
        if (factory == null || !factory.getClass().getName().equals(PREFIX + "DefaultProcessDefinitionFactory")) return null;
        Object cache = SapProcessDefinitions.field(factory,"definitionsCache");
        if (cache == null || !cache.getClass().getName().equals(PREFIX + "ProcessDefinitionsCache")) return null;
        Object provider = SapProcessDefinitions.field(cache,"processDefinitionsProvider");
        Object reader = SapProcessDefinitions.field(factory,"xmlDefinitionsReader");
        if (provider == null || (creation && !provider.getClass().getName().equals(PREFIX + "ProcessDefinitionsProvider"))
                || SapProcessDefinitions.field(provider,"applicationContext") != context
                || reader == null || !reader.getClass().getName().equals(PREFIX + "XMLProcessDefinitionsReader")) return null;
        return new Native(context,bf,factoryName,factory,cache,provider,reader,loader,
                (ReadWriteLock) SapProcessDefinitions.field(cache,"definitionsLock"));
    }
    private static boolean current(Native n) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(n.context.getClass().getMethod("isActive").invoke(n.context))
                && n.context.getClass().getMethod("getBeanFactory").invoke(n.context) == n.beanFactory
                && n.beanFactory.getClass().getMethod("getSingleton",String.class).invoke(n.beanFactory,n.factoryName) == n.factory
                && SapProcessDefinitions.field(n.factory,"definitionsCache") == n.cache
                && SapProcessDefinitions.field(n.cache,"processDefinitionsProvider") == n.provider
                && SapProcessDefinitions.field(n.provider,"applicationContext") == n.context
                && SapProcessDefinitions.field(n.factory,"xmlDefinitionsReader") == n.reader;
    }

    private static String name(Object definition) throws ReflectiveOperationException {
        return SapProcessDefinitions.text(definition, "getName");
    }
    private static String version(Object definition) throws ReflectiveOperationException {
        return SapProcessDefinitions.text(definition, "getVersion");
    }
    private static String tag(Object context) {
        return "context@" + Integer.toHexString(System.identityHashCode(context));
    }
    private static Outcome refuse(Path path, Object context, String reason) {
        String detail = tag(context) + ": " + reason;
        synchronized (refusals) {
            refusals.removeIf(entry -> entry.context.get() == null);
            Refusal entry = refusals.stream().filter(r -> r.path.equals(path)
                    && r.context.get() == context && r.reason.equals(reason)).findFirst().orElse(null);
            if (entry == null) {
                // The ledger subject is a basename; an opaque token keeps distinct paths separate.
                entry = new Refusal(path, new WeakReference<>(context), reason,
                        "SAP process definition was not applied: " + detail + " [refusal#" + ++nextRefusal + "]");
                if (refusals.size() == MAX_REFUSALS) refusals.remove(0);
                refusals.add(entry);
            }
            RestartLedger.note(path.getFileName().toString(), entry.ledgerReason);
        }
        StatusReporter.warn("SAP process definition " + path.getFileName() + " MODIFIED: not applied; restart required. " + detail);
        return Outcome.ADVICE;
    }
    private static void resolve(Path path, Object context) {
        synchronized (refusals) {
            refusals.removeIf(entry -> {
                Object owner = entry.context.get();
                if (owner == context && entry.path.equals(path)) {
                    RestartLedger.resolve(path.getFileName().toString(), entry.ledgerReason);
                    return true;
                }
                return owner == null;
            });
        }
    }
    private static Outcome refuse(Path path, String reason) {
        StatusReporter.warn("SAP process definition " + path.getFileName() + " MODIFIED: not applied; restart required. " + reason);
        RestartLedger.note(path.getFileName().toString(), "SAP process definition was not applied: " + reason);
        return Outcome.ADVICE;
    }
}
