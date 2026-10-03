/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.bootstrap.CacheDependencyLedger;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import java.lang.reflect.Method;
import java.util.*;

/** Refreshes the default OCC field selection consumer without rebuilding Orika schemas. */
final class SapFieldSetRefresher {
    private static final String MAPPING = "de.hybris.platform.webservicescommons.mapping.config.FieldSetLevelMapping";
    private static final String HELPER = "de.hybris.platform.webservicescommons.mapping.impl.DefaultFieldSetLevelHelper";
    private static final String HELPER_API = "de.hybris.platform.webservicescommons.mapping.FieldSetLevelHelper";
    private SapFieldSetRefresher() { }

    static boolean isMapping(Object value) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(MAPPING)) return true;
        return false;
    }

    static List<String> refresh(Object owner, Set<Object> changed) {
        if (changed.isEmpty()) return List.of();
        List<String> failures = new ArrayList<>();
        List<Object> contexts = new ArrayList<>(ApplicationContextHolder.getAllContexts());
        if (!contexts.contains(owner)) contexts.add(owner);
        for (Object context : contexts) {
            try {
                if (!Boolean.TRUE.equals(call(context, "isActive"))) continue;
                Object factory = call(context, "getBeanFactory");
                ClassLoader loader = (ClassLoader) call(factory, "getBeanClassLoader");
                Class<?> mappingType;
                try { mappingType = Class.forName(MAPPING, false, loader); }
                catch (ClassNotFoundException absent) { continue; }
                Map<String, Object> mappings = beans(context, mappingType);
                if (mappings.values().stream().noneMatch(changed::contains)) continue;
                Class<?> helperApi = Class.forName(HELPER_API, false, loader);
                List<Object> helpers = new ArrayList<>();
                for (String name : (String[]) call(factory, "getSingletonNames")) {
                    Object singleton = call(factory, "getSingleton", String.class, name);
                    if (helperApi.isInstance(singleton)) helpers.add(singleton);
                }
                if (helpers.isEmpty()) continue; // A future lazy helper builds from updated definitions.
                Class<?> helperType = Class.forName(HELPER, false, loader);
                for (Object helper : helpers)
                    if (helper.getClass() != helperType)
                        throw new IllegalStateException("custom field-set helper requires restart");
                Object scratch = helperType.getConstructor().newInstance();
                call(scratch, "setLevelMap", Map.class, new HashMap<>());
                Method add = helperType.getDeclaredMethod("addToLevelMap", mappingType);
                add.setAccessible(true);
                for (var entry : mappings.entrySet()) {
                    Object liveMapping = entry.getValue();
                    if (liveMapping.getClass() != mappingType)
                        throw new IllegalStateException("custom field-set mapping requires restart");
                    Object definingFactory = definingFactory(context, entry.getKey());
                    // The original definition is updated by XML reload; Spring's merged cache may still contain the old map.
                    Object definition = call(definingFactory, "getBeanDefinition", String.class, entry.getKey());
                    Object raw = null;
                    for (Object pv : SpringReflection.getPropertyValueArray(SpringReflection.getPropertyValues(definition)))
                        if ("levelMapping".equals(SpringReflection.getPropertyName(pv))) raw = SpringReflection.getPropertyValue(pv);
                    if (!(raw instanceof Map<?, ?>))
                        throw new IllegalStateException("field-set refresh requires an inline levelMapping map");
                    Object resolved = SpringReflection.resolveValue(definingFactory, entry.getKey(), definition, "levelMapping", raw);
                    if (!(resolved instanceof Map<?, ?> values)) throw new IllegalStateException("levelMapping is not a map");
                    Map<String, String> copy = new LinkedHashMap<>();
                    for (var level : values.entrySet()) {
                        if (!(level.getKey() instanceof String key) || !(level.getValue() instanceof String value))
                            throw new IllegalStateException("levelMapping must contain string levels and fields");
                        copy.put(key, value);
                    }
                    Object mapping = mappingType.getConstructor().newInstance();
                    call(mapping, "setDtoClass", Class.class, call(liveMapping, "getDtoClass"));
                    call(mapping, "setLevelMapping", Map.class, copy);
                    add.invoke(scratch, mapping);
                }
                Object prepared = call(scratch, "getLevelMap");
                for (Object helper : helpers) call(helper, "setLevelMap", Map.class, prepared);
                Class<?> managerApi = Class.forName("org.springframework.cache.CacheManager", false, loader);
                boolean cacheFound = false;
                for (Object manager : beans(context, managerApi).values()) {
                    Collection<?> names = (Collection<?>) managerApi.getMethod("getCacheNames").invoke(manager);
                    if (!names.contains("fieldSetCache")) continue;
                    Object cache = managerApi.getMethod("getCache", String.class).invoke(manager, "fieldSetCache");
                    cacheFound = true;
                    if (cache == null || !CacheDependencyLedger.clearObserved(cache))
                        throw new IllegalStateException("fieldSetCache invalidation failed");
                }
                if (!cacheFound) throw new IllegalStateException("no manager-backed fieldSetCache; cache refresh cannot be verified");
            } catch (Exception failure) {
                failures.add("OCC field selection refresh failed: " + SpringReflection.rootCause(failure));
            }
        }
        return failures;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> beans(Object context, Class<?> type) throws ReflectiveOperationException {
        ClassLoader loader = context.getClass().getClassLoader();
        Class<?> api = Class.forName("org.springframework.beans.factory.ListableBeanFactory", false, loader);
        Class<?> utility = Class.forName("org.springframework.beans.factory.BeanFactoryUtils", false, loader);
        return (Map<String, Object>) utility.getMethod("beansOfTypeIncludingAncestors", api, Class.class)
                .invoke(null, context, type);
    }

    private static Object definingFactory(Object context, String name) throws ReflectiveOperationException {
        for (Object current = context; current != null; current = call(current, "getParent")) {
            Object factory = call(current, "getBeanFactory");
            if (Boolean.TRUE.equals(call(factory, "containsBeanDefinition", String.class, name))) return factory;
        }
        throw new IllegalStateException("field-set mapping has no bean definition");
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }
    private static Object call(Object target, String name, Class<?> type, Object value) throws ReflectiveOperationException {
        return target.getClass().getMethod(name, type).invoke(target, value);
    }
}
