/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.hybris.PlatformTenant;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Keeps SAP's registered mapping identities across Spring bean recreation. */
public class InterceptorReloader {
    private static final String MAPPING = "de.hybris.platform.servicelayer.interceptor.impl.InterceptorMapping";

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
        return new Refresh(List.of(), false);
    }

    // Exercises the actual SAP registry SPI without starting a database in SDK contract tests.
    Refresh prepareInContext(String className, Object context, ClassLoader loader) {
        List<MappingChange> changes = new ArrayList<>();
        try {
            Class<?> type = Class.forName(className, false, loader);
            Class<?> mappingType = Class.forName(MAPPING, false, loader);
            Method getBean = context.getClass().getMethod("getBean", String.class);
            Method names = context.getClass().getMethod("getBeanNamesForType", Class.class);
            Object registry = getBean.invoke(context, "interceptorRegistry");
            Method register = registry.getClass().getMethod("registerInterceptor", mappingType);
            Method unregister = registry.getClass().getMethod("unregisterInterceptor", mappingType);
            String[] beanNames = (String[]) names.invoke(context, type);
            for (String name : (String[]) names.invoke(context, mappingType)) {
                Object mapping = getBean.invoke(context, name);
                Object target = mappingType.getMethod("getInterceptor").invoke(mapping);
                for (String beanName : beanNames) {
                    if (target == getBean.invoke(context, beanName)
                            || (beanNames.length == 1 && type.isInstance(target))) {
                        changes.add(new MappingChange(context, getBean, name, beanName,
                                mapping, target, registry, register, unregister, mappingType));
                        break;
                    }
                }
            }
            if (changes.isEmpty()) StatusReporter.warn("No registered Spring mapping found for interceptor: " + className);
            return new Refresh(changes, !changes.isEmpty());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            StatusReporter.warn("Interceptor mapping capture failed: " + className);
            return new Refresh(List.of(), false);
        }
    }

    private record MappingChange(Object context, Method getBean, String mappingName, String beanName,
                                 Object oldMapping, Object oldTarget, Object registry,
                                 Method register, Method unregister, Class<?> mappingType) {}

    public static final class Refresh {
        private final List<MappingChange> changes;
        private final boolean ready;
        private boolean completed;
        private Refresh(List<MappingChange> changes, boolean ready) {
            this.changes = List.copyOf(changes);
            this.ready = ready;
        }

        private static void verifyTarget(Object registry, Class<?> mappingType, Object mapping, Object target)
                throws ReflectiveOperationException {
            String typeCode = (String) mappingType.getMethod("getTypeCode").invoke(mapping);
            boolean checked = false;
            for (String kind : List.of("Validate", "Prepare", "Load", "Remove", "InitDefaults")) {
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
            boolean success = true;
            for (MappingChange change : changes) {
                Object next = null;
                boolean removed = false;
                try {
                    next = change.getBean.invoke(change.context, change.mappingName);
                    Object target = change.getBean.invoke(change.context, change.beanName);
                    Method setter = change.mappingType.getMethod("setInterceptor",
                            change.mappingType.getMethod("getInterceptor").getReturnType());
                    Object currentRegistry = change.getBean.invoke(change.context, "interceptorRegistry");
                    if (currentRegistry != change.registry) {
                        // Spring may recreate the dependent registry as well. Its
                        // configured mappings are loaded lazily: never add a duplicate.
                        verifyTarget(currentRegistry, change.mappingType, next, target);
                        continue;
                    }
                    // SAP unregister removes the original mapping by identity, not bean name.
                    change.unregister.invoke(change.registry, change.oldMapping);
                    removed = true;
                    setter.invoke(next, target);
                    change.register.invoke(change.registry, next);
                    verifyTarget(change.registry, change.mappingType, next, target);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    success = false;
                    if (removed) {
                        try {
                            if (next != null) change.unregister.invoke(change.registry, next);
                            // Keep the registry and Spring on the same mapping
                            // identity so a later save can retry the failed target.
                            Object restored = next != null ? next : change.oldMapping;
                            change.mappingType.getMethod("setInterceptor",
                                    change.mappingType.getMethod("getInterceptor").getReturnType())
                                    .invoke(restored, change.oldTarget);
                            change.register.invoke(change.registry, restored);
                        } catch (ReflectiveOperationException rollback) {
                            StatusReporter.error("Interceptor mapping restoration failed; restart required: " + change.mappingName);
                        }
                    }
                    StatusReporter.warn("Interceptor re-registration failed; restart may be required: " + change.mappingName);
                    RestartLedger.note(change.mappingName, "interceptor re-registration failed");
                }
            }
            return success;
        }
    }
}
