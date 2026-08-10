/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Evicts Spring caches for classes with @Cacheable, @CacheEvict, or @CachePut annotations.
 *
 * When a class using Spring caching annotations is reloaded, cached values may become stale.
 * This reloader finds all CacheManager beans and evicts their caches.
 *
 * Note: currently evicts ALL caches, not just caches referenced by the reloaded class.
 * Targeted eviction would require parsing @Cacheable("name") annotation values via reflection,
 * which is complex and brittle. Full eviction is safe for development.
 *
 * All Spring interaction is via reflection — graceful no-op if Spring Cache is not present.
 */
public class SpringCacheReloader {

    private final PlatformContext platformContext;

    public SpringCacheReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Evict caches if the reloaded class uses Spring caching annotations.
     */
    public boolean reloadCaches(Class<?> reloadedClass) {
        if (!hasCacheAnnotations(reloadedClass)) return false;

        boolean evicted = false;
        // CacheManagers may live in any context (web contexts included).
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            evicted |= reloadCachesIn(appContext, reloadedClass);
        }
        if (evicted) {
            StatusReporter.success("Spring caches evicted for " + reloadedClass.getName());
        }
        return evicted;
    }

    private boolean reloadCachesIn(Object appContext, Class<?> reloadedClass) {
        try {
            // Get all CacheManager beans
            String[] beanNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.cache.CacheManager");
            if (beanNames == null || beanNames.length == 0) return false;

            boolean evicted = false;
            for (String beanName : beanNames) {
                evicted |= evictAllCaches(appContext, beanName);
            }
            return evicted;
        } catch (Exception e) {
            StatusReporter.warn("Spring cache eviction failed: " + e.getMessage());
            return false;
        }
    }

    private boolean hasCacheAnnotations(Class<?> clazz) {
        try {
            for (var annotation : clazz.getAnnotations()) {
                String name = annotation.annotationType().getName();
                if (name.contains("Cacheable") || name.contains("CacheEvict") ||
                        name.contains("CachePut") || name.contains("CacheConfig") ||
                        name.contains("EnableCaching")) {
                    return true;
                }
            }
            // Also check methods
            for (var method : clazz.getDeclaredMethods()) {
                for (var annotation : method.getAnnotations()) {
                    String name = annotation.annotationType().getName();
                    if (name.contains("Cacheable") || name.contains("CacheEvict") ||
                            name.contains("CachePut")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean evictAllCaches(Object appContext, String cacheManagerBeanName) throws Exception {
        Method getBean = appContext.getClass().getMethod("getBean", String.class);
        Object cacheManager = getBean.invoke(appContext, cacheManagerBeanName);

        Method getCacheNames = cacheManager.getClass().getMethod("getCacheNames");
        Collection<String> cacheNames = (Collection<String>) getCacheNames.invoke(cacheManager);

        Method getCache = cacheManager.getClass().getMethod("getCache", String.class);

        boolean evicted = false;
        for (String cacheName : cacheNames) {
            Object cache = getCache.invoke(cacheManager, cacheName);
            if (cache != null) {
                Method clear = cache.getClass().getMethod("clear");
                clear.invoke(cache);
                evicted = true;
            }
        }
        return evicted;
    }
}
