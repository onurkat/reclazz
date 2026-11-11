/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.platform.PlatformTenant;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.Failures;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Keeps SAP's registered mapping identities across Spring bean recreation. */
public class InterceptorReloader {
    private static final String MAPPING = "de.hybris.platform.servicelayer.interceptor.impl.InterceptorMapping";

    private static final List<String> KINDS = List.of("Validate", "Prepare", "Load", "Remove", "InitDefaults");
    // Context keys are weak: a closed application must not be retained by diagnostics.
    private static final Map<Object, Set<String>> skipped = new WeakHashMap<>();

    private static void reportSkip(Object owner, String mapping) {
        synchronized (skipped) {
            if (!skipped.computeIfAbsent(owner, ignored -> new HashSet<>()).add(mapping)) return;
        }
        StatusReporter.info("Interceptor mapping skipped (type absent in owner), not refreshed: " + mapping);
    }

    /** Query before mutation. Only SAP's exact missing-type failure means inapplicable. */
    private static boolean applicable(List<Object> registries, Class<?> mappingType, Object mapping, Object target)
            throws ReflectiveOperationException {
        if (registries.isEmpty()) throw new IllegalStateException("Owner interceptor registry unavailable");
        String typeCode = (String) mappingType.getMethod("getTypeCode").invoke(mapping);
        int available = 0, absent = 0;
        Throwable missing = null;
        for (String kind : KINDS) {
            Class<?> api = Class.forName("de.hybris.platform.servicelayer.interceptor." + kind + "Interceptor",
                    false, mappingType.getClassLoader());
            if (!api.isInstance(target)) continue;
            for (Object registry : registries) {
                try {
                    Object result = registry.getClass().getMethod("get" + kind + "Interceptors", String.class)
                            .invoke(registry, typeCode);
                    if (!(result instanceof Collection<?>)) throw new IllegalStateException("Interceptor query did not return a collection");
                    available++;
                } catch (InvocationTargetException failure) {
                    Throwable cause = failure.getCause();
                    Class<?> missingType = Class.forName("de.hybris.platform.jalo.JaloItemNotFoundException",
                            false, mappingType.getClassLoader());
                    if (cause == null || cause.getClass() != missingType) throw failure;
                    absent++;
                    missing = cause;
                }
            }
        }
        if (available + absent == 0) throw new IllegalStateException("No supported interceptor contract on target");
        if (available > 0 && absent > 0)
            throw new IllegalStateException("Inconsistent interceptor type availability across owner registries or contracts", missing);
        return absent == 0;
    }

    /** Called before Spring destroys beans; Registry belongs to the application's loader and tenant. */
    public Refresh prepare(String className, List<Object> contexts) {
        for (Object context : contexts) {
            try {
                ClassLoader loader = (ClassLoader) context.getClass().getMethod("getClassLoader").invoke(context);
                Class<?> registry = Class.forName("de.hybris.platform.core.Registry", false, loader);
                if (!PlatformTenant.ensureActive(loader)) continue;
                Object tenantContext = registry.getMethod("getApplicationContext").invoke(null);
                if (tenantContext != null) return prepareInContext(className, tenantContext, loader);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                // Another captured context may belong to the platform loader.
            }
        }
        StatusReporter.warn("Interceptor registry unavailable; re-registration was not performed: " + className);
        return new Refresh(List.of(), false, false);
    }

    // Exercises the actual SAP registry SPI without starting a database in SDK contract tests.
    public Refresh prepareInContext(String className, Object context, ClassLoader loader) {
        List<MappingChange> changes = new ArrayList<>();
        boolean matched = false, valid = true;
        try {
            Class<?> type = Class.forName(className, false, loader);
            Class<?> mappingType = Class.forName(MAPPING, false, loader);
            Method getBean = context.getClass().getMethod("getBean", String.class);
            Method names = context.getClass().getMethod("getBeanNamesForType", Class.class);
            List<Object> registries = InterceptorRegistries.resolve(context);
            String[] beanNames = (String[]) names.invoke(context, type);
            for (String name : (String[]) names.invoke(context, mappingType)) {
                Object mapping = getBean.invoke(context, name);
                Object target = mappingType.getMethod("getInterceptor").invoke(mapping);
                for (String beanName : beanNames) {
                    if (target == getBean.invoke(context, beanName)
                            || (beanNames.length == 1 && type.isInstance(target))) {
                        matched = true;
                        try {
                            if (applicable(registries, mappingType, mapping, target))
                                changes.add(new MappingChange(context, getBean, name, beanName,
                                        mapping, target, registries, mappingType));
                            else reportSkip(context, name);
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                            valid = false;
                            String reason = "interceptor mapping capture failed: " + Failures.describe(failure);
                            StatusReporter.warn("Interceptor mapping capture failed: " + name + ": " + Failures.describe(failure));
                            RestartLedger.note(name, reason);
                        }
                        break;
                    }
                }
            }
            if (!matched) StatusReporter.warn("No registered Spring mapping found for interceptor: " + className);
            return new Refresh(changes, matched, valid);
        } catch (Exception | LinkageError e) {
            String reason = "interceptor mapping capture failed: " + Failures.describe(e);
            StatusReporter.warn("Interceptor mapping capture failed: " + className + ": " + Failures.describe(e));
            RestartLedger.note(className, reason);
            return new Refresh(List.of(), false, false);
        }
    }

    private record MappingChange(Object context, Method getBean, String mappingName, String beanName,
                                 Object oldMapping, Object oldTarget, List<Object> registries, Class<?> mappingType) {}

    public static final class Refresh {
        private final List<MappingChange> changes;
        private final boolean ready;
        private final boolean valid;
        private boolean completed;
        private Refresh(List<MappingChange> changes, boolean ready, boolean valid) {
            this.changes = List.copyOf(changes);
            this.ready = ready;
            this.valid = valid;
        }

        private static void verifyTarget(Object registry, Class<?> mappingType, Object mapping, Object target)
                throws ReflectiveOperationException {
            String typeCode = (String) mappingType.getMethod("getTypeCode").invoke(mapping);
            boolean checked = false;
            for (String kind : KINDS) {
                Class<?> api = Class.forName("de.hybris.platform.servicelayer.interceptor." + kind + "Interceptor",
                        false, mappingType.getClassLoader());
                if (!api.isInstance(target)) continue;
                Collection<?> registered = (Collection<?>) registry.getClass()
                        .getMethod("get" + kind + "Interceptors", String.class).invoke(registry, typeCode);
                if (registered.stream().filter(value -> value == target).count() != 1)
                    throw new IllegalStateException("Registry did not expose the refreshed target exactly once");
                checked = true;
            }
            if (!checked) throw new IllegalStateException("No supported interceptor contract on target");
        }

        /** False includes missing mappings and failures; callers must not label either a success. */
        public synchronized boolean complete() {
            if (!ready || completed) return false;
            completed = true;
            boolean success = valid;
            for (MappingChange change : changes) {
                Object next = null;
                boolean removed = false;
                try {
                    next = change.getBean.invoke(change.context, change.mappingName);
                    Object target = change.getBean.invoke(change.context, change.beanName);
                    Method setter = change.mappingType.getMethod("setInterceptor",
                            change.mappingType.getMethod("getInterceptor").getReturnType());
                    // Capture precedes generic destruction: never instantiate a replacement registry here.
                    for (Object registry : change.registries) {
                        registry.getClass().getMethod("unregisterInterceptor", change.mappingType).invoke(registry, change.oldMapping);
                        removed = true;
                    }
                    setter.invoke(next, target);
                    for (Object registry : change.registries) {
                        registry.getClass().getMethod("registerInterceptor", change.mappingType).invoke(registry, next);
                        verifyTarget(registry, change.mappingType, next, target);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    success = false;
                    String reason = "interceptor re-registration failed: " + Failures.describe(e);
                    if (removed) {
                        try {
                            for (Object registry : change.registries) if (next != null)
                                registry.getClass().getMethod("unregisterInterceptor", change.mappingType).invoke(registry, next);
                            // Keep the registry and Spring on the same mapping
                            // identity so a later save can retry the failed target.
                            Object restored = next != null ? next : change.oldMapping;
                            change.mappingType.getMethod("setInterceptor",
                                    change.mappingType.getMethod("getInterceptor").getReturnType())
                                    .invoke(restored, change.oldTarget);
                            for (Object registry : change.registries)
                                registry.getClass().getMethod("registerInterceptor", change.mappingType).invoke(registry, restored);
                        } catch (ReflectiveOperationException | RuntimeException rollback) {
                            reason += "; restoration failed: " + Failures.describe(rollback);
                            StatusReporter.error("Interceptor mapping restoration failed; restart required: "
                                    + change.mappingName + ": " + reason);
                        }
                    }
                    StatusReporter.warn("Interceptor re-registration failed; restart may be required: "
                            + change.mappingName + ": " + reason);
                    RestartLedger.note(change.mappingName, reason);
                }
            }
            return success;
        }
    }
}
