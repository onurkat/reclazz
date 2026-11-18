/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.Plural;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Spring 7 caches resolved singleton handlers beyond their factory lifetime. */
final class MvcResolvedHandlerCache {
    private MvcResolvedHandlerCache() { }

    static void refresh(List<Object> contexts, SpringMvcReloader fallback) {
        int cleared = 0;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object context : contexts) {
            Object factory = SpringBeans.getBeanFactory(context);
            if (factory == null) continue;
            try {
                var names = Reflect.findMethod(factory.getClass(), "getSingletonNames");
                var singleton = Reflect.findMethod(factory.getClass(), "getSingleton", String.class);
                if (names == null || singleton == null) continue;
                for (String name : (String[]) names.invoke(factory)) {
                    Object mapping = singleton.invoke(factory, name);
                    if (mapping == null || !seen.add(mapping)) continue;
                    Class<?> type;
                    try {
                        type = Class.forName("org.springframework.web.servlet.handler.AbstractHandlerMethodMapping",
                                false, mapping.getClass().getClassLoader());
                    } catch (ClassNotFoundException absent) { continue; }
                    if (type.isInstance(mapping)) cleared += invalidate(factory, mapping, fallback, true);
                }
            } catch (Exception failure) {
                StatusReporter.warn("MVC resolved handler scan unavailable: " + failure);
            }
        }
        if (cleared > 0) ReloadEffects.note(Plural.of(cleared, "controller handler") + " re-resolved");
    }

    /** The unlocked mode exists only for the deterministic race negative control. */
    static int invalidate(Object factory, Object mapping, SpringMvcReloader fallback, boolean lockRegistry) {
        var write = SpringMvcReloader.registryWriteLock(mapping);
        if (lockRegistry && write != null) write.lock();
        try {
            var methods = Reflect.findMethod(mapping.getClass(), "getHandlerMethods");
            if (methods == null) return 0;
            int cleared = 0;
            var rescans = new LinkedHashMap<String, Class<?>>();
            for (Object handler : ((Map<?, ?>) methods.invoke(mapping)).values()) {
                String name = "unknown";
                try {
                    var cache = cacheField(handler.getClass());
                    if (cache == null) continue; // Spring 5/6 have no resolved singleton cache.
                    Object resolved = cache.get(handler);
                    if (resolved == null && (!lockRegistry || write != null)) continue;
                    Object owner = handler.getClass().getMethod("getBean").invoke(handler);
                    if (!(owner instanceof String beanName)) continue;
                    name = beanName;
                    if (resolved == null) {
                        // Without the shared lock, an old resolution may still be about to store.
                        Class<?> beanType = (Class<?>) handler.getClass().getMethod("getBeanType").invoke(handler);
                        rescans.put(name, beanType);
                        continue;
                    }
                    Object cachedBean = resolved.getClass().getMethod("getBean").invoke(resolved);
                    String canonical = SpringSingletonPreservation.canonical(factory, name);
                    var contains = Reflect.findMethod(factory.getClass(), "containsSingleton", String.class);
                    var singleton = Reflect.findMethod(factory.getClass(), "getSingleton", String.class, boolean.class);
                    if (contains == null || singleton == null) throw new IllegalStateException("singleton lookup unavailable");
                    Object current = (Boolean) contains.invoke(factory, canonical)
                            ? singleton.invoke(factory, canonical, false) : null;
                    if (current == cachedBean) continue;
                    if (lockRegistry && write == null) {
                        Class<?> beanType = (Class<?>) handler.getClass().getMethod("getBeanType").invoke(handler);
                        rescans.put(name, beanType);
                        continue;
                    }
                    if (Modifier.isFinal(cache.getModifiers()) || Modifier.isStatic(cache.getModifiers()))
                        throw new IllegalStateException("resolved handler cache is not writable");
                    cache.set(handler, null);
                    cleared++;
                } catch (Exception failure) { warn(name); }
            }
            for (var entry : rescans.entrySet()) {
                try {
                    if (fallback == null || !fallback.rescan(mapping, entry.getKey(), entry.getValue())) warn(entry.getKey());
                } catch (Exception failure) { warn(entry.getKey()); }
            }
            return cleared;
        } catch (Exception failure) {
            StatusReporter.warn("MVC resolved handler scan unavailable: " + failure);
            return 0;
        } finally {
            if (lockRegistry && write != null) write.unlock();
        }
    }

    private static Field cacheField(Class<?> type) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField("resolvedBeanHandlerMethod");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) { /* Try its parent. */ }
        }
        return null;
    }

    private static void warn(String name) {
        RestartLedger.note("MVC handler " + name, "controller reference could not be re-resolved");
        StatusReporter.warn("MVC handler '" + name + "' could not be re-resolved; restart or save the controller to pick up the new bean");
    }
}
