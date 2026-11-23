/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

/**
 * R35: Spring caches each class's bean properties (CachedIntrospectionResults) for the class loader. After a reload that
 * adds or removes members, the loader's entries are dropped so the next BeanWrapper or BeanUtils call sees the current
 * getters and setters, through Spring's own public API.
 */
final class SpringBeanPropertyCaches {
    private SpringBeanPropertyCaches() { }

    static void flush(Class<?> owner) {
        ClassLoader loader = owner.getClassLoader();
        try {
            Class<?> cached = Class.forName("org.springframework.beans.CachedIntrospectionResults", false, loader);
            cached.getMethod("clearClassLoader", ClassLoader.class).invoke(null, loader);
        } catch (ClassNotFoundException | LinkageError noSpringBeans) {
            // Nothing cached by Spring for this loader.
        } catch (Throwable failure) {
            String reason = "Spring's bean property cache could not be cleared for "
                    + owner.getName() + " (" + failure.getClass().getSimpleName() + "); a restart shows added properties to BeanWrapper";
            com.onurkat.reclazz.ui.StatusReporter.warn(reason);
            com.onurkat.reclazz.ui.RestartLedger.note(owner.getName(), reason);
        }
    }
}
