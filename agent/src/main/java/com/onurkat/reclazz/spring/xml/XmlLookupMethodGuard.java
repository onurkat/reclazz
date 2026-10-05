/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.util.*;

/** Existing generated lookup subclasses cannot adopt a changed XML declaration in place. */
final class XmlLookupMethodGuard {
    private XmlLookupMethodGuard() { }

    static boolean classify(String name, Object previous, Object candidate, BeanDefinitionDiff diff) {
        if (previous == null) return false; // Native creation handles new lookup beans.
        try {
            if (lookups(previous).equals(lookups(candidate))) return false;
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                    "lookup-method declarations changed; restart required, bean and definition were not updated"));
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                    "cannot verify lookup-method metadata; restart required: "
                            + SpringReflection.rootCause(failure)));
        }
        return true;
    }

    private static Map<String, List<String>> lookups(Object definition) throws ReflectiveOperationException {
        Object overrides = definition.getClass().getMethod("getMethodOverrides").invoke(definition);
        Collection<?> entries = (Collection<?>) overrides.getClass().getMethod("getOverrides").invoke(overrides);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (!entry.getClass().getName().equals("org.springframework.beans.factory.support.LookupOverride")) continue;
            String method = (String) entry.getClass().getMethod("getMethodName").invoke(entry);
            String target = (String) entry.getClass().getMethod("getBeanName").invoke(entry);
            // Order matters for repeated declarations of the same method (last match
            // wins). Independent methods can move without changing their meaning.
            result.computeIfAbsent(method, ignored -> new ArrayList<>()).add(target);
        }
        return result;
    }
}
