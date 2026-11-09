/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.platform.XmlBeanLifecycle;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Native mapping identity reconciliation for XML additions, setters and recreation. */
public final class XmlInterceptorMappings implements XmlBeanLifecycle {
    private static final String MAPPING = "de.hybris.platform.servicelayer.interceptor.impl.InterceptorMapping";
    private static final String REGISTRY = "de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry";

    public static boolean isMapping(Object value) {
        for (Class<?> type = value == null ? null : value.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(MAPPING)) return true;
        return false;
    }

    @Override public Pending before(Object context, String name, Object previous) {
        if (previous != null && !isMapping(previous)) return NONE.before(context, name, previous);
        List<Registration> captured = new java.util.ArrayList<>();
        if (previous != null) {
            try {
                captured.addAll(registrations(context, previous));
                for (Registration state : captured) state.remove(previous);
            } catch (Exception | LinkageError failure) {
                for (Registration state : captured) restore(name, state, previous);
                report(name, failure);
                return NONE.before(context, name, previous);
            }
        }
        List<Registration> old = List.copyOf(captured);
        return new Pending() {
            private boolean completed;
            @Override public synchronized void complete(Object current, boolean applied) {
                if (completed) return;
                completed = true;
                if (!isMapping(current)) {
                    for (Registration state : old) restore(name, state, previous);
                    return;
                }
                List<Registration> next = new java.util.ArrayList<>();
                try {
                    next.addAll(registrations(context, current));
                    for (Registration state : next) state.add(current);
                    StatusReporter.success("Spring bean " + name + ": interceptor mapping "
                            + (previous == null ? "registered" : "re-registered"));
                } catch (Exception | LinkageError failure) {
                    report(name, failure);
                    for (Registration state : next) {
                        try { state.remove(current); }
                        catch (Exception cleanup) { report(name, cleanup); }
                    }
                    for (Registration state : old) restore(name, state, previous);
                }
            }
        };
    }

    private static void restore(String name, Registration old, Object previous) {
        try {
            old.restoreFields(previous);
            old.add(previous);
        } catch (Exception | LinkageError failure) { report(name, failure); }
    }

    private static List<Registration> registrations(Object context, Object mapping) throws Exception {
        Class<?> mappingType = mapping.getClass();
        while (!mappingType.getName().equals(MAPPING)) mappingType = mappingType.getSuperclass();
        List<Registration> result = new java.util.ArrayList<>();
        for (Object registry : InterceptorRegistries.resolve(context)) result.add(registration(registry, mapping, mappingType));
        return List.copyOf(result);
    }

    private static Registration registration(Object registry, Object mapping, Class<?> mappingType) throws Exception {
        Class<?> nativeType = registry.getClass();
        while (nativeType != null && !nativeType.getName().equals(REGISTRY)) nativeType = nativeType.getSuperclass();
        if (nativeType == null) throw new IllegalStateException("Unsupported interceptor registry");
        // Getters warm the lazily configured registry before inspecting identities.
        Object target = mappingType.getMethod("getInterceptor").invoke(mapping);
        String typeCode = (String) mappingType.getMethod("getTypeCode").invoke(mapping);
        boolean supported = false;
        for (String kind : List.of("Validate", "Prepare", "Load", "Remove", "InitDefaults")) {
            Class<?> api = Class.forName("de.hybris.platform.servicelayer.interceptor." + kind + "Interceptor",
                    false, mappingType.getClassLoader());
            if (api.isInstance(target)) {
                registry.getClass().getMethod("get" + kind + "Interceptors", String.class).invoke(registry, typeCode);
                supported = true;
            }
        }
        if (!supported) throw new IllegalStateException("No supported interceptor target");
        Field buckets = nativeType.getDeclaredField("typeToInterceptorsMap");
        if (!buckets.trySetAccessible()) throw new IllegalStateException("Mapping identities unavailable");
        return new Registration(registry, mappingType, buckets,
                typeCode, target, (Integer) mappingType.getMethod("getOrder").invoke(mapping),
                List.copyOf((Collection<?>) mappingType.getMethod("getReplacedInterceptors").invoke(mapping)));
    }

    private record Registration(Object registry, Class<?> mappingType, Field buckets,
                                String typeCode, Object target, int order, Collection<?> replaced) {
        private int count(Object mapping) throws Exception {
            int count = 0;
            for (Object bucket : ((Map<?, ?>) buckets.get(registry)).values()) {
                Field mappings = bucket.getClass().getDeclaredField("mappings");
                if (!mappings.trySetAccessible()) throw new IllegalStateException("Mapping identities unavailable");
                for (Object entry : (Collection<?>) mappings.get(bucket)) if (entry == mapping) count++;
            }
            return count;
        }
        private void add(Object mapping) throws Exception {
            int count = count(mapping);
            if (count == 0) registry.getClass().getMethod("registerInterceptor", mappingType).invoke(registry, mapping);
            if (count(mapping) != 1) throw new IllegalStateException("Mapping was not registered exactly once");
        }
        private void remove(Object mapping) throws Exception {
            int count = count(mapping);
            if (count > 1) throw new IllegalStateException("Duplicate mapping identity requires restart");
            if (count == 1) registry.getClass().getMethod("unregisterInterceptor", mappingType).invoke(registry, mapping);
            if (count(mapping) != 0) throw new IllegalStateException("Old mapping identity remains registered");
        }
        private void restoreFields(Object mapping) throws Exception {
            mappingType.getMethod("setTypeCode", String.class).invoke(mapping, typeCode);
            Method getter = mappingType.getMethod("getInterceptor");
            mappingType.getMethod("setInterceptor", getter.getReturnType()).invoke(mapping, target);
            mappingType.getMethod("setOrder", int.class).invoke(mapping, order);
            mappingType.getMethod("setReplacedInterceptors", Collection.class).invoke(mapping, replaced);
        }
    }

    private static void report(String name, Throwable failure) {
        String reason = "interceptor mapping registration failed; restart required: " + Failures.describe(failure);
        StatusReporter.warn("Spring bean " + name + ": " + reason);
        RestartLedger.note(name, reason);
    }
}
