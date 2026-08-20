/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Clears Spring's cached {@code @Transactional} and {@code @Cacheable}
 * metadata after a reload, so an edited annotation takes effect.
 *
 * <p>Spring resolves the transaction attribute and the cache operations of a
 * method once and keeps the result in {@code attributeCache}, a map inside
 * {@code AbstractFallbackTransactionAttributeSource} and
 * {@code AbstractFallbackCacheOperationSource}, keyed by method and class.
 * Redefinition changes what the annotation says but not the identity of the
 * class or its {@code Method} objects, so the map keeps answering with what
 * the annotation used to say: a {@code @Transactional(readOnly = true)}
 * flipped to writable still runs read-only, a {@code @Cacheable} given a new
 * condition still caches under the old one, and nothing anywhere reports it.
 * Of every reloader here, this was the only metadata cache left uncleared.
 *
 * <p>The whole map is cleared rather than the reloaded class's entries picked
 * out of it: the key type is internal, both sources repopulate on the next
 * call from the (now current) annotations, and both cache the absence of an
 * annotation too, so a method that just gained one is exactly as stale as a
 * method that changed one. All reflective, no Spring dependency: an
 * application without transactions or caching has no such beans and nothing
 * here runs.
 */
public class SpringOperationSourceReloader {

    /** The types whose beans carry an {@code attributeCache} worth clearing. */
    private static final String[] SOURCE_TYPES = {
            "org.springframework.transaction.interceptor.TransactionAttributeSource",
            "org.springframework.cache.interceptor.CacheOperationSource",
    };

    private final PlatformContext platformContext;

    public SpringOperationSourceReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Clear every operation-metadata cache that could hold a stale answer for
     * the reloaded class.
     *
     * @param annotationsChanged whether this reload changed annotations, which
     *                           is the case where the developer would notice;
     *                           the clearing itself is unconditional because it
     *                           is a cache and a spurious clear costs one
     *                           re-resolution
     * @return how many caches had entries and were cleared
     */
    public int reloadOperationSources(Class<?> reloadedClass, boolean annotationsChanged) {
        if (reloadedClass == null) return 0;

        // Named per source, because the two can succeed independently and a
        // combined line was measured claiming a clear that had not happened.
        java.util.Set<String> cleared = new java.util.LinkedHashSet<>();
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            for (String type : SOURCE_TYPES) {
                String[] names = SpringBeans.beanNamesForType(appContext, type);
                if (names == null) continue;
                for (String name : names) {
                    try {
                        Object source = appContext.getClass()
                                .getMethod("getBean", String.class)
                                .invoke(appContext, name);
                        if (clearAttributeCache(source)) {
                            cleared.add(type.contains("transaction") ? "transaction" : "cache");
                        }
                    } catch (Throwable ignored) {
                        // A source that cannot be read keeps its cache; the
                        // stale-metadata window stays what it was before this
                        // class existed, and the reload itself is unaffected.
                    }
                }
            }
        }

        if (!cleared.isEmpty() && annotationsChanged) {
            StatusReporter.success(String.join("/", cleared)
                    + " annotation metadata re-read for " + reloadedClass.getName());
        }
        return cleared.size();
    }

    /**
     * Finds the metadata cache up the class hierarchy and clears it.
     *
     * <p>The transaction source calls its map {@code attributeCache}; the
     * cache source called it that too until Spring Framework 6.1 renamed its
     * copy to {@code operationCache}, which was measured the hard way: the
     * transaction half of this cleared and the cache half silently did not,
     * while one success line claimed both. So both names are tried, and when
     * neither is there, any single Map-typed field whose name ends in
     * {@code Cache} is taken as the rename it will be next time. A Spring
     * that moves further away makes this a no-op, never an error.
     */
    static boolean clearAttributeCache(Object source) {
        if (source == null) return false;
        for (Class<?> c = source.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field field = mapField(c, "attributeCache");
            if (field == null) field = mapField(c, "operationCache");
            if (field == null) {
                Field byShape = null;
                for (Field candidate : c.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(candidate.getType())) continue;
                    if (!candidate.getName().endsWith("Cache")) continue;
                    if (byShape != null) { byShape = null; break; }   // ambiguous: touch nothing
                    byShape = candidate;
                }
                field = byShape;
            }
            if (field == null) continue;                              // keep walking up
            try {
                field.setAccessible(true);
                Map<?, ?> cache = (Map<?, ?>) field.get(source);
                if (cache == null || cache.isEmpty()) return false;
                cache.clear();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static Field mapField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            return Map.class.isAssignableFrom(field.getType()) ? field : null;
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }
}
