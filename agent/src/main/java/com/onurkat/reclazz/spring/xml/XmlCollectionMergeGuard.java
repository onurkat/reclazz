/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.lang.reflect.Method;
import java.util.*;

/** Raw child values cannot be used as a replacement for Spring's merged property values. */
final class XmlCollectionMergeGuard {
    private XmlCollectionMergeGuard() { }

    private record Value(String type, Object content, boolean merge, boolean containsMerge) { }

    static boolean classify(String name, Object previous, Object candidate, BeanDefinitionDiff diff) {
        if (previous == null) return false; // Native creation merges new definitions itself.
        try {
            Class<?> mergeable = Class.forName("org.springframework.beans.Mergeable", false,
                    candidate.getClass().getClassLoader());
            Method enabled = mergeable.getMethod("isMergeEnabled");
            Map<String, Object> oldValues = properties(previous), newValues = properties(candidate);
            Set<String> names = new LinkedHashSet<>(oldValues.keySet());
            names.addAll(newValues.keySet());
            for (String property : names) {
                Value oldValue = snapshot(oldValues.get(property), mergeable, enabled, 0);
                Value newValue = snapshot(newValues.get(property), mergeable, enabled, 0);
                if ((oldValue.containsMerge || newValue.containsMerge) && !oldValue.equals(newValue)) {
                    diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                            "merged collection property '" + property
                                    + "' changed; restart required, bean and definition were not updated"));
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                    "cannot verify merged collection metadata; restart required: "
                            + SpringReflection.rootCause(failure)));
            return true;
        }
    }

    private static Map<String, Object> properties(Object definition) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Object pv : SpringReflection.getPropertyValueArray(SpringReflection.getPropertyValues(definition)))
            values.put(SpringReflection.getPropertyName(pv), SpringReflection.getPropertyValue(pv));
        return values;
    }

    private static Value snapshot(Object raw, Class<?> mergeable, Method enabled, int depth)
            throws ReflectiveOperationException {
        if (depth > 32) throw new IllegalArgumentException("nested property depth exceeds verification bound");
        boolean merge = mergeable.isInstance(raw) && (Boolean) enabled.invoke(raw);
        boolean containsMerge = merge;
        Object content;
        if (raw instanceof Map<?, ?> map) {
            List<Value> entries = new ArrayList<>();
            for (var entry : map.entrySet()) {
                Value key = snapshot(entry.getKey(), mergeable, enabled, depth + 1);
                Value value = snapshot(entry.getValue(), mergeable, enabled, depth + 1);
                entries.add(key); entries.add(value);
                containsMerge |= key.containsMerge || value.containsMerge;
            }
            content = entries;
        } else if (raw instanceof Collection<?> collection) {
            List<Value> values = new ArrayList<>();
            for (Object item : collection) {
                Value value = snapshot(item, mergeable, enabled, depth + 1);
                values.add(value); containsMerge |= value.containsMerge;
            }
            content = values;
        } else {
            // Preserve the existing scalar comparison convention; collection metadata
            // is separate because ManagedList/Map.toString() omits the merge flag.
            content = String.valueOf(raw);
        }
        return new Value(raw == null ? "null" : raw.getClass().getName(), content, merge, containsMerge);
    }
}
