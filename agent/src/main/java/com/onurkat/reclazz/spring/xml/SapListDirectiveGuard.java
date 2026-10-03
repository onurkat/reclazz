/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

/** SDK list directives mutate other beans at initialization; setters cannot replay them safely. */
final class SapListDirectiveGuard {
    private SapListDirectiveGuard() { }

    static boolean classify(Object factory, String name, Object previous, Object candidate,
                            BeanDefinitionDiff diff) {
        if (previous == null) return false; // New directives retain their existing initialization path.
        try {
            Class<?> type = (Class<?>) factory.getClass().getMethod("getType", String.class, boolean.class)
                    .invoke(factory, name, false);
            boolean directive = false;
            for (; type != null; type = type.getSuperclass()) {
                String className = type.getName();
                if (className.equals("de.hybris.platform.converters.impl.ModifyPopulatorList")
                        || className.equals("de.hybris.platform.spring.config.ListMergeDirective")) {
                    directive = true;
                    break;
                }
            }
            if (!directive) return false;
            if (!normalized(previous).equals(normalized(candidate))) {
                diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                        "SAP list directive edit requires restart; effective target lists were not refreshed"));
            }
            return true;
        } catch (ReflectiveOperationException failure) {
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                    "cannot verify XML bean type/definition: " + SpringReflection.rootCause(failure)));
            return true;
        }
    }

    private static Object normalized(Object definition) throws ReflectiveOperationException {
        Object copy = definition.getClass().getMethod("cloneBeanDefinition").invoke(definition);
        // Spring can resolve the original class name to Class during startup.
        copy.getClass().getMethod("setBeanClassName", String.class)
                .invoke(copy, SpringReflection.getBeanClassName(definition));
        return copy;
    }
}
