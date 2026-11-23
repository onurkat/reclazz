/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * R35: each SpEL ReflectivePropertyAccessor caches the getters, setters and fields it found per class (readerCache,
 * writerCache, typeDescriptorCache, sortedMethodsCache; same names in Spring 5.3 to 7). Live accessors drop them after a
 * reload that changes members, so `#{dto.added}` and `@Value` expressions see the current class.
 */
final class SpelCaches {
    private SpelCaches() { }
    private static final String[] CACHES = {"readerCache", "writerCache", "typeDescriptorCache", "sortedMethodsCache"};

    static void flush() {
        for (Object accessor : com.onurkat.reclazz.bootstrap.TrackedInstances.live("spel")) {
            for (Class<?> c = accessor.getClass(); c != null; c = c.getSuperclass()) {
                if (!c.getName().equals("org.springframework.expression.spel.support.ReflectivePropertyAccessor")) continue;
                for (String name : CACHES) {
                    try {
                        Field field = c.getDeclaredField(name);
                        field.setAccessible(true);
                        if (field.get(accessor) instanceof Map<?, ?> map) map.clear();
                    } catch (Throwable unreadable) {
                        com.onurkat.reclazz.ui.StatusReporter.warn("A SpEL property cache could not be cleared ("
                                + unreadable.getClass().getSimpleName() + "); new evaluation contexts see the changed members");
                        return;
                    }
                }
            }
        }
    }
}
