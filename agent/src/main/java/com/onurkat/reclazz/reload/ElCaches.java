/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * R35: an EL BeanELResolver (JSP and JSF `${dto.added}`) caches a class's bean properties by class name: Tomcat's
 * (also SAP Commerce's el-api) per resolver in {@code cache} (a ConcurrentCache with {@code eden} and {@code longterm}
 * maps, Jakarta and javax alike), GlassFish's in one static {@code properties} map. Both are cleared after a reload that
 * changes members.
 */
final class ElCaches {
    private ElCaches() { }

    static void flush() {
        java.util.Set<Class<?>> staticCleared = new java.util.HashSet<>();
        for (Object resolver : com.onurkat.reclazz.bootstrap.TrackedInstances.live("el")) {
            try {
                Class<?> type = resolver.getClass();
                while (type != null && !type.getSimpleName().equals("BeanELResolver")) type = type.getSuperclass();
                if (type == null) continue;
                // GlassFish's javax.el keeps one static map for all resolvers ("properties").
                if (java.util.Arrays.stream(type.getDeclaredFields()).noneMatch(f -> f.getName().equals("cache"))) {
                    if (staticCleared.add(type)) {
                        Field properties = type.getDeclaredField("properties");
                        properties.setAccessible(true);
                        Object entries = properties.get(null);
                        if (entries instanceof Map<?, ?> map) map.clear();
                        // Its SoftConcurrentHashMap keeps the entries in an inner "map", not in the map it extends.
                        for (Field inner : entries.getClass().getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(inner.getModifiers()) || !Map.class.isAssignableFrom(inner.getType())) continue;
                            inner.setAccessible(true);
                            if (inner.get(entries) instanceof Map<?, ?> map) map.clear();
                        }
                    }
                    continue;
                }
                Field cacheField = type.getDeclaredField("cache");
                cacheField.setAccessible(true);
                Object cache = cacheField.get(resolver);
                for (String name : new String[]{"eden", "longterm"}) {
                    Field map = cache.getClass().getDeclaredField(name);
                    map.setAccessible(true);
                    if (map.get(cache) instanceof Map<?, ?> entries) synchronized (entries) { entries.clear(); }
                }
            } catch (Throwable unreadable) {
                String reason = "An EL bean property cache could not be cleared ("
                        + unreadable.getClass().getSimpleName() + "); a restart shows added properties to JSP expressions";
                com.onurkat.reclazz.ui.StatusReporter.warn(reason);
                com.onurkat.reclazz.ui.RestartLedger.note("EL bean properties", reason);
            }
        }
    }
}
