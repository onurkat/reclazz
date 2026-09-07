/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.bootstrap.CacheDependencyLedger;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.Collection;
import com.onurkat.reclazz.ui.Failures;

/** Evicts observed dependent cache instances, with the original annotation fallback for unknown coverage. */
public class SpringCacheReloader {

    private final PlatformContext platformContext;

    public SpringCacheReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Evict caches if the reloaded class uses Spring caching annotations.
     */
    public boolean reloadCaches(Class<?> reloadedClass) {
        if (reloadedClass == null) return false;
        java.util.List<Object> known = java.util.List.of();
        boolean complete = false;
        try {
            known = CacheDependencyLedger.cachesDependingOn(reloadedClass);
            complete = CacheDependencyLedger.completeCoverage();
        } catch (LinkageError unavailableBootstrap) { /* Keep the original annotation fallback. */ }
        boolean annotated = hasCacheAnnotations(reloadedClass);
        boolean evicted = false;
        java.util.List<String> names = new java.util.ArrayList<>();
        // Clear known resolver-only caches too, even when manager fallback is needed.
        for (Object cache : known) {
            if (CacheDependencyLedger.clearObserved(cache)) {
                evicted = true;
                names.add(CacheDependencyLedger.cacheName(cache));
            }
        }
        if (!annotated || (complete && !known.isEmpty())) {
            if (evicted) {
                ReloadEffects.note("caches evicted");
                StatusReporter.detail("Spring caches evicted for " + reloadedClass.getName() + ": " + String.join(", ", names));
            }
            return evicted;
        }


        // CacheManagers may live in any context (web contexts included).
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            evicted |= reloadCachesIn(appContext, reloadedClass);
        }
        if (evicted) {
            ReloadEffects.note("caches evicted");
            StatusReporter.detail("Spring caches evicted for " + reloadedClass.getName());
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
            StatusReporter.warn("Spring cache eviction failed: " + Failures.describe(e));
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
