/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.hibernate;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Invalidates Hibernate L2 cache for modified entity/DAO classes after structural reload.
 * Uses reflection to access SessionFactory and Cache, since Hibernate classes are loaded
 * by the platform classloader.
 */
public class HibernateCacheInvalidator {

    /**
     * Attempt to evict all entity data from Hibernate L2 cache.
     * Called after a structural reload of an entity or DAO class.
     */
    public void invalidateCache(String className) {
        try {
            Object sessionFactory = getSessionFactory();
            if (sessionFactory == null) return;

            Method getCacheMethod = sessionFactory.getClass().getMethod("getCache");
            Object cache = getCacheMethod.invoke(sessionFactory);
            if (cache == null) return;

            // Try to evict specific entity class
            try {
                Class<?> entityClass = Class.forName(className, false,
                        sessionFactory.getClass().getClassLoader());
                Method evictEntityData = findMethod(cache.getClass(), "evictEntityData", Class.class);
                if (evictEntityData != null) {
                    evictEntityData.invoke(cache, entityClass);
                    StatusReporter.info("Hibernate L2 cache evicted for: " + className);
                    return;
                }
            } catch (ClassNotFoundException ignored) {
                // Not an entity class
            }

            // Fallback: evict all entity data
            Method evictAll = findMethod(cache.getClass(), "evictAllRegions");
            if (evictAll != null) {
                evictAll.invoke(cache);
                StatusReporter.info("Hibernate L2 cache fully evicted after reload of: " + className);
            }

        } catch (Exception e) {
            StatusReporter.warn("Hibernate cache invalidation skipped: " + e.getMessage());
        }
    }

    private Object getSessionFactory() {
        try {
            // Access via Hybris Registry -> Spring context -> sessionFactory bean
            Class<?> registryClass = Class.forName("de.hybris.platform.core.Registry");
            Method hasCurrentTenant = registryClass.getMethod("hasCurrentTenant");
            if (!(Boolean) hasCurrentTenant.invoke(null)) return null;

            Method getCtx = registryClass.getMethod("getApplicationContext");
            Object appContext = getCtx.invoke(null);
            if (appContext == null) return null;

            Method getBean = appContext.getClass().getMethod("getBean", String.class);
            return getBean.invoke(appContext, "sessionFactory");
        } catch (Exception e) {
            return null;
        }
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method m = current.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
