/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import java.lang.reflect.Field;
import java.util.*;

/** Tracks the native dependency edges introduced while resolving one converter's new list. */
final class ConverterDependencySnapshot {
    private final Map<String, Set<String>> dependents;
    private final Map<String, Set<String>> dependencies;
    private final Map<String, Set<String>> contained;
    private final String converter;
    private final Set<String> referenced;
    private final Set<String> originalDependencies;
    private final Set<String> originalContained;

    ConverterDependencySnapshot(Object factory, String converter, Set<String> referenced) throws ReflectiveOperationException {
        Class<?> registry = Class.forName("org.springframework.beans.factory.support.DefaultSingletonBeanRegistry",
                false, factory.getClass().getClassLoader());
        if (!registry.isInstance(factory)) throw new IllegalStateException("unsupported singleton registry");
        this.dependents = nativeMap(registry, factory, "dependentBeanMap");
        this.dependencies = nativeMap(registry, factory, "dependenciesForBeanMap");
        this.contained = nativeMap(registry, factory, "containedBeanMap");
        this.converter = converter;
        this.referenced = Set.copyOf(referenced);
        this.originalDependencies = members(dependencies, converter);
        this.originalContained = members(contained, converter);
        // Native registration writes the two directions separately. Never admit an inconsistent baseline.
        for (String member : referenced) {
            if (members(dependents, member).contains(converter) != originalDependencies.contains(member))
                throw new IllegalStateException("inconsistent converter dependency graph");
        }
    }

    /** Disconnect the old converter before destroying any staged member or owned inner bean. */
    Set<String> undoResolution() {
        var introducedInner = new LinkedHashSet<>(members(contained, converter));
        introducedInner.removeAll(originalContained);
        var rollbackMembers = new LinkedHashSet<>(referenced);
        rollbackMembers.addAll(introducedInner);
        for (String dependency : rollbackMembers) {
            if (!originalDependencies.contains(dependency)) {
                remove(dependents, dependency, converter);
                remove(dependencies, converter, dependency);
            }
        }
        for (String inner : introducedInner) remove(contained, converter, inner);
        return introducedInner;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> nativeMap(Class<?> registry, Object factory, String name)
            throws ReflectiveOperationException {
        Field field = registry.getDeclaredField(name);
        if (field.getType() != Map.class || !field.trySetAccessible() || !(field.get(factory) instanceof Map<?, ?> map))
            throw new IllegalStateException("unavailable singleton registry map " + name);
        return (Map<String, Set<String>>) map;
    }

    private static Set<String> members(Map<String, Set<String>> map, String key) {
        synchronized (map) {
            return Set.copyOf(map.getOrDefault(key, Set.of()));
        }
    }

    private static void remove(Map<String, Set<String>> map, String key, String value) {
        synchronized (map) {
            Set<String> current = map.get(key);
            if (current != null && current.remove(value) && current.isEmpty()) map.remove(key);
        }
    }
}
