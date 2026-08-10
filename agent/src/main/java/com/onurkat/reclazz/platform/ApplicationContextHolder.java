/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Static holder for Spring ApplicationContext instances.
 *
 * For non-Hybris Spring applications, ApplicationContexts are captured by
 * SpringContextInterceptTransformer which instruments AbstractApplicationContext.refresh()
 * to call register() after the context is initialized.
 *
 * Uses WeakReferences so contexts can be garbage collected normally.
 */
public class ApplicationContextHolder {

    private static final List<WeakReference<Object>> contexts = new CopyOnWriteArrayList<>();

    /**
     * Register an ApplicationContext. Called by instrumented AbstractApplicationContext.refresh().
     */
    public static void register(Object applicationContext) {
        if (applicationContext == null) return;

        // Remove stale references and check for duplicates
        contexts.removeIf(ref -> {
            Object existing = ref.get();
            return existing == null || existing == applicationContext;
        });

        contexts.add(new WeakReference<>(applicationContext));
    }

    /**
     * Get the first live ApplicationContext.
     *
     * @return the ApplicationContext, or null if none registered or all collected
     */
    public static Object getApplicationContext() {
        for (WeakReference<Object> ref : contexts) {
            Object ctx = ref.get();
            if (ctx != null) {
                return ctx;
            }
        }
        return null;
    }

    /**
     * Get all live ApplicationContexts.
     */
    public static List<Object> getAllContexts() {
        List<Object> result = new java.util.ArrayList<>();
        contexts.removeIf(ref -> ref.get() == null);
        for (WeakReference<Object> ref : contexts) {
            Object ctx = ref.get();
            if (ctx != null) {
                result.add(ctx);
            }
        }
        return result;
    }

    /**
     * Clear all registered contexts.
     */
    public static void clear() {
        contexts.clear();
    }
}
