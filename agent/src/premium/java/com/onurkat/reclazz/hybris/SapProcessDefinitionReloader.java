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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

/** Native parse then guarded publication. Discovery and the startup observer never call this. */
public final class SapProcessDefinitionReloader implements PlatformChangeHandler {
    private static final String PREFIX = "de.hybris.platform.processengine.definition.";

    @Override public Outcome handle(Change change) {
        if (!"MODIFIED".equals(change.eventType())) return new AdviceOnlyChangeHandler().handle(change);
        Path path = change.path().toAbsolutePath().normalize();
        Thread thread = Thread.currentThread();
        ClassLoader previousLoader = thread.getContextClassLoader();
        try {
            List<SapProcessResources.Registration> registrations = SapProcessResources.registrations();
            var owners = registrations.stream().filter(r -> r.path().equals(path)).toList();
            if (owners.size() != 1) return refuse(path, "registered process resource ownership is ambiguous or unavailable");
            var owner = owners.get(0);
            Object context = owner.context();
            Object beanFactory = context.getClass().getMethod("getBeanFactory").invoke(context);
            ClassLoader loader = (ClassLoader) beanFactory.getClass().getMethod("getBeanClassLoader").invoke(beanFactory);
            // Native JAXB and provider services resolve dependencies through the context loader.
            thread.setContextClassLoader(loader);
            Class<?> factoryType = Class.forName(PREFIX + "ProcessDefinitionFactory", false, loader);
            Object factory = null;
            String factoryName = null;
            for (String name : (String[]) beanFactory.getClass().getMethod("getSingletonNames").invoke(beanFactory)) {
                Object bean = beanFactory.getClass().getMethod("getSingleton", String.class).invoke(beanFactory, name);
                if (factoryType.isInstance(bean)) {
                    if (factory != null && factory != bean) return refuse(path, "multiple process definition factories");
                    factory = bean;
                    factoryName = name;
                }
            }
            if (factory == null || !factory.getClass().getName().equals(PREFIX + "DefaultProcessDefinitionFactory"))
                return refuse(path, "custom process definition factory or factory unavailable");
            Object cache = SapProcessDefinitions.field(factory, "definitionsCache");
            if (cache == null || !cache.getClass().getName().equals(PREFIX + "ProcessDefinitionsCache"))
                return refuse(path, "custom process definition factory cache");
            Object provider = SapProcessDefinitions.field(cache, "processDefinitionsProvider");
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
                if (observed.size() != 1) return refuse(path,
                        "this file was loaded before Reclazz could observe it; restart to load it");
                baseline = observed.get(0);
                idType = Class.forName(PREFIX + "ProcessDefinitionId", false, loader);
                activeId = idType.getConstructor(String.class).newInstance(baseline.name());
                active = ((Map<?, ?>) raw).get(activeId);
                if (active == null || !baseline.version().equals(version(active))) {
                    SapProcessDefinitions.replace(cache, provider, context, baseline, null);
                    return refuse(path, "active process definition was replaced elsewhere; startup association discarded");
                }
                reader = SapProcessDefinitions.field(factory, "xmlDefinitionsReader");
                if (reader == null || !reader.getClass().getName().equals(PREFIX + "XMLProcessDefinitionsReader"))
                    return refuse(path, "custom process definition reader");
            } finally { lock.readLock().unlock(); }
            // Native parsing may resolve application beans. It must not hold a cache lock.
            Class<?> resourceType = Class.forName("org.springframework.core.io.Resource", false, loader);
            var parse = reader.getClass().getMethod("from", resourceType);
            Object candidate = parse.invoke(reader, owner.resource());
            String name = name(candidate);
            if (!baseline.name().equals(name)) return refuse(path, "process name changed from " + baseline.name() + " to " + name);
            for (var other : SapProcessDefinitions.observed(cache, provider, context)) {
                if (other.path().equals(path)) continue;
                if (name.equals(other.name()))
                    return refuse(path, "multiple registered resources define process " + name);
            }
            if (provider == null || provider.getClass().getMethod("getLatestDefinitionIdFor", idType)
                    .invoke(provider, activeId) != null)
                return refuse(path, "active DB-backed DynamicProcessDefinition for " + name);
            String version = version(candidate);
            if (version == null) return refuse(path, "native reader returned no content version");
            lock.writeLock().lock();
            try {
                if (!Boolean.TRUE.equals(context.getClass().getMethod("isActive").invoke(context)))
                    return refuse(path, "application context closed during parsing");
                Object current = SapProcessDefinitions.field(cache, "definitions");
                if (context.getClass().getMethod("getBeanFactory").invoke(context) != beanFactory
                        || beanFactory.getClass().getMethod("getSingleton", String.class).invoke(beanFactory, factoryName) != factory
                        || SapProcessDefinitions.field(factory, "definitionsCache") != cache
                        || SapProcessDefinitions.field(cache, "processDefinitionsProvider") != provider
                        || current == null || ((Map<?, ?>) current).get(activeId) != active) {
                    SapProcessDefinitions.replace(cache, provider, context, baseline, null);
                    return refuse(path, "process definition ownership changed during validation");
                }
                if (version.equals(version(active))) {
                    StatusReporter.info("SAP process " + name + " unchanged in " + path.getFileName());
                    return Outcome.IGNORED;
                }
                Class<?> definitionType = Class.forName(PREFIX + "ProcessDefinition", false, loader);
                factory.getClass().getMethod("add", definitionType).invoke(factory, candidate);
                SapProcessDefinitions.replace(cache, provider, context, baseline,
                        new SapProcessDefinitions.Definition(path, name, version));
                StatusReporter.info("SAP process " + name + " reloaded from " + path.getFileName()
                        + ": processes started from now on use the edited definition; "
                        + "processes already started keep the version they started with.");
                return Outcome.HANDLED;
            } finally { lock.writeLock().unlock(); }
        } catch (Exception | LinkageError unavailable) {
            Throwable cause = unavailable;
            for (int depth = 0; depth < 16 && cause.getCause() != null && cause.getCause() != cause; depth++)
                cause = cause.getCause();
            // Native parser messages can contain absolute resource URLs. Keep local paths out of the report.
            String message = cause.getMessage();
            if (message != null) message = message.replaceAll("(?:file:)?/[^\\s'\"<>]+", "<resource>");
            return refuse(path, "native process validation failed: " + cause.getClass().getSimpleName()
                    + (message == null ? "" : " " + message));
        } finally { thread.setContextClassLoader(previousLoader); }
    }

    private static String name(Object definition) throws ReflectiveOperationException {
        return SapProcessDefinitions.text(definition, "getName");
    }
    private static String version(Object definition) throws ReflectiveOperationException {
        return SapProcessDefinitions.text(definition, "getVersion");
    }
    private static Outcome refuse(Path path, String reason) {
        StatusReporter.warn("SAP process definition " + path.getFileName() + " MODIFIED: not applied; restart required. " + reason);
        RestartLedger.note(path.getFileName().toString(), "SAP process definition was not applied: " + reason);
        return Outcome.ADVICE;
    }
}
