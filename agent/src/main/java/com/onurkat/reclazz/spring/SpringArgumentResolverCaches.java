/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

/**
 * Drops what Spring worked out about a handler method's parameters.
 *
 * <p>The name, the default value and whether a parameter is required are read
 * off {@code @RequestParam} once and cached, keyed by the {@code MethodParameter}.
 * A reload re-registers the mapping and builds fresh {@code MethodParameter}
 * objects, which does not help: that key compares the method and the index, so
 * a fresh one equals the old one and finds the stale answer. Measured on Spring
 * Boot 3.3.4, stock JDK 21, changing a default on a handler that had already
 * served a request:
 *
 * <pre>
 *   before                          GET /param -> v=alpha
 *   defaultValue alpha to beta      reload succeeds, mappings re-scanned
 *   after                           GET /param -> v=alpha   (and nothing said why)
 * </pre>
 *
 * <p>Two caches, on two kinds of object. Each resolver that reads a named value
 * keeps its own {@code namedValueInfoCache}, and the composite that picks a
 * resolver per parameter keeps {@code argumentResolverCache}. The second only
 * matters when a parameter's annotation changes which resolver should handle it
 * at all, which is rarer and just as stale, and it costs one map to empty.
 *
 * <p>Reached from the controller reload, since a parameter annotation is not a
 * class annotation and nothing else would notice it changed. It says nothing
 * when it works: a default value that now applies is what the developer wrote.
 */
final class SpringArgumentResolverCaches {

    private static final String HANDLER_ADAPTER = "org.springframework.web.servlet.HandlerAdapter";

    private static final String REQUEST_MAPPING_ADAPTER = "RequestMappingHandlerAdapter";

    /** The lists of resolvers an adapter holds, composite or plain. */
    private static final String[] RESOLVER_HOLDERS = {
            "argumentResolvers", "initBinderArgumentResolvers", "returnValueHandlers",
    };

    private static final String[] CACHES = {
            "namedValueInfoCache", "argumentResolverCache", "returnValueHandlerCache",
    };

    private SpringArgumentResolverCaches() {
    }

    /**
     * @return how many caches held something and were emptied
     */
    static int flush(List<Object> applicationContexts) {
        int cleared = 0;
        for (Object appContext : applicationContexts) {
            for (String name : SpringBeans.beanNamesForType(appContext, HANDLER_ADAPTER)) {
                Object adapter = SpringBeans.getBean(appContext, name);
                if (adapter == null) continue;
                if (!adapter.getClass().getName().endsWith(REQUEST_MAPPING_ADAPTER)) continue;
                cleared += clearOn(adapter);
            }
        }
        return cleared;
    }

    /** The adapter's own caches, and those of every resolver it holds. */
    static int clearOn(Object adapter) {
        int cleared = emptyCaches(adapter);
        for (String holder : RESOLVER_HOLDERS) {
            Object composite = read(adapter, holder);
            if (composite == null) continue;
            cleared += emptyCaches(composite);
            for (Object resolver : elementsOf(composite)) {
                cleared += emptyCaches(resolver);
            }
        }
        return cleared;
    }

    /** Whatever the composite is holding, or nothing when it is not one. */
    private static List<Object> elementsOf(Object composite) {
        List<Object> found = new java.util.ArrayList<>();
        for (Class<?> c = composite.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(composite) instanceof List<?> list) {
                        for (Object element : list) {
                            if (element != null) found.add(element);
                        }
                    }
                } catch (Throwable notReadable) {
                    // A composite that will not be read holds what it holds.
                }
            }
        }
        return found;
    }

    private static int emptyCaches(Object target) {
        int cleared = 0;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : CACHES) {
                Field field;
                try {
                    field = c.getDeclaredField(name);
                } catch (NoSuchFieldException notOnThisOne) {
                    continue;
                }
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Map<?, ?> cache = (Map<?, ?>) field.get(target);
                    if (cache == null || cache.isEmpty()) continue;
                    cache.clear();
                    cleared++;
                } catch (Throwable oneCache) {
                    // A cache that cannot be emptied keeps what it holds.
                }
            }
        }
        return cleared;
    }

    private static Object read(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException keepWalking) {
                // the next class up may declare it
            } catch (Throwable notReadable) {
                return null;
            }
        }
        return null;
    }
}
