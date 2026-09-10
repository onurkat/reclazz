/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.hibernate;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.Plural;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Optional Hibernate ORM support. Commerce's native persistence cache is a different API. */
public class HibernateCacheInvalidator {
    public void invalidateCache(String className) {
        invalidateCache(className, ApplicationContextHolder.getAllContexts());
    }

    /** Number of distinct factories successfully evicted; missing ORM is not a successful eviction. */
    public int invalidateCache(String className, List<Object> contexts) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int evicted = 0;
        for (Object context : contexts) {
            try {
                ClassLoader loader = (ClassLoader) context.getClass().getMethod("getClassLoader").invoke(context);
                Class<?> factoryType;
                try { factoryType = Class.forName("org.hibernate.SessionFactory", false, loader); }
                catch (ClassNotFoundException absent) { continue; }
                Class<?> cacheType = Class.forName("org.hibernate.Cache", false, loader);
                var names = context.getClass().getMethod("getBeanNamesForType", Class.class);
                var getBean = context.getClass().getMethod("getBean", String.class);
                for (String name : (String[]) names.invoke(context, factoryType)) {
                    Object factory = getBean.invoke(context, name);
                    if (!visited.add(factory)) continue;
                    try {
                        if (Boolean.TRUE.equals(factoryType.getMethod("isClosed").invoke(factory))) continue;
                        Object options = factoryType.getMethod("getSessionFactoryOptions").invoke(factory);
                        Class<?> optionsType = Class.forName("org.hibernate.boot.spi.SessionFactoryOptions", false, loader);
                        if (!Boolean.TRUE.equals(optionsType.getMethod("isSecondLevelCacheEnabled").invoke(options))) continue;
                        Object cache = factoryType.getMethod("getCache").invoke(factory);
                        if (cache == null) continue;
                        // A DAO is not a mapped entity. An entity-only eviction can
                        // also leave query, collection and natural-id regions stale.
                        cacheType.getMethod("evictAllRegions").invoke(cache);
                        evicted++;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        StatusReporter.warn("Hibernate cache eviction failed for factory: " + name);
                    }
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                StatusReporter.warn("Hibernate cache lookup failed in a Spring context after reload of: " + className);
            }
        }
        if (evicted > 0) StatusReporter.info("Hibernate cache regions evicted in " + Plural.of(evicted, "SessionFactory instance")
                + " after reload of: " + className);
        return evicted;
    }
}
