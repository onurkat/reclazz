/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.interceptor;

import java.util.*;

/** Only native mapping singletons and the initialized canonical registry in this exact factory. */
public final class InterceptorSingletons {
    private InterceptorSingletons() { }
    public static Set<String> preserved(Object context) throws Exception {
        Object factory = InterceptorRegistries.factory(context);
        Set<String> result = new LinkedHashSet<>();
        Object registry = InterceptorRegistries.singleton(factory, "interceptorRegistry");
        if (isRegistry(registry)) result.add((String) factory.getClass().getMethod("canonicalName", String.class)
                .invoke(factory, "interceptorRegistry"));
        for (String name : InterceptorRegistries.names(factory))
            if (XmlInterceptorMappings.isMapping(InterceptorRegistries.singleton(factory, name)))
                result.add((String) factory.getClass().getMethod("canonicalName", String.class).invoke(factory, name));
        return Set.copyOf(result);
    }

    /** Inspect already loaded types: context resource loaders need not see the optional native API. */
    private static boolean isRegistry(Object value) {
        if (value == null) return false;
        Deque<Class<?>> pending = new ArrayDeque<>(); pending.add(value.getClass());
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (!seen.add(type)) continue;
            if (type.getName().equals("de.hybris.platform.servicelayer.interceptor.InterceptorRegistry")) return true;
            if (type.getSuperclass() != null) pending.add(type.getSuperclass());
            pending.addAll(Arrays.asList(type.getInterfaces()));
        }
        return false;
    }
}
