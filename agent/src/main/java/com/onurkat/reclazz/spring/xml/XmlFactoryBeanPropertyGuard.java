/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

/** Factory configuration does not refresh Spring's cached product or its consumers. */
final class XmlFactoryBeanPropertyGuard {
    private XmlFactoryBeanPropertyGuard() { }

    static boolean classify(Object factory, String name, Object previous, Object candidate,
                            BeanDefinitionDiff diff) {
        if (previous == null) return false; // New definitions retain native creation.
        try {
            Object existing = SpringReflection.getExistingSingleton(factory, name);
            Class<?> type;
            if (existing != null) {
                type = existing.getClass();
            } else {
                // Merged metadata resolves inherited classes without getBean/getType,
                // either of which can create a FactoryBean or query its product.
                Object merged = factory.getClass().getMethod("getMergedBeanDefinition", String.class).invoke(factory, name);
                String className = (String) merged.getClass().getMethod("getBeanClassName").invoke(merged);
                ClassLoader loader = (ClassLoader) factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
                if (className == null) throw new IllegalArgumentException("existing bean class unavailable");
                type = Class.forName(className, false, loader);
            }
            Class<?> factoryBean = Class.forName("org.springframework.beans.factory.FactoryBean", false,
                    factory.getClass().getClassLoader());
            if (!factoryBean.isAssignableFrom(type)) return false;
            var comparator = new XmlRawValueComparator(candidate.getClass().getClassLoader());
            if (!comparator.verifiedStandardDefinition(previous).equals(comparator.verifiedStandardDefinition(candidate))) {
                diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                        "FactoryBean definition changed; factory/product state and consumers were not refreshed, "
                                + "restart required, bean and definition were not updated"));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(name,
                    "cannot verify FactoryBean definition safety; restart required, bean and definition were not updated: "
                            + SpringReflection.rootCause(unavailable)));
        }
        return true;
    }
}
