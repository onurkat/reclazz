/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.platform.ApplicationContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drops the JAXB contexts SAP Commerce's OCC message converters hold for a
 * class whose shape changed.
 *
 * <p>{@code Jaxb2HttpMessageConverter} builds one EclipseLink MOXy context per
 * response type and keeps it in {@code jaxbContexts} for the life of the
 * converter. The context fixes the type's properties when it is built, so a
 * property added to a WsDTO was mapped by Orika, set on the object, and left
 * out of the JSON. Measured on SAP Commerce 2211: a DTO returned with the new
 * property set came back without it until a restart.
 *
 * <p>A context is dropped only when one of its descriptors is the changed
 * class or a subclass of it, so the next response for that type builds a new
 * context the way the first one was built. Nothing is added to SAP's classes:
 * the converters are found among the singletons the contexts already hold and
 * in each MVC adapter's converter list, which is where OCC's live ones are.
 */
final class JaxbContextCaches {
    static final String CONVERTER = "de.hybris.platform.webservicescommons.jaxb.Jaxb2HttpMessageConverter";
    private static final String ADAPTER = "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter";

    private JaxbContextCaches() {
    }

    /** @return how many cached contexts were dropped */
    static int evict(Class<?> changed) {
        int dropped = 0;
        for (Object converter : converters(ApplicationContextHolder.getAllContexts())) {
            Map<?, ?> cache = cache(converter);
            if (cache == null) continue;
            for (var entry : new ArrayList<>(cache.entrySet())) {
                if (covers(entry.getKey(), entry.getValue(), changed) && cache.remove(entry.getKey(), entry.getValue()))
                    dropped++;
            }
        }
        return dropped;
    }

    static List<Object> converters(List<Object> contexts) {
        Map<Object, Boolean> found = new IdentityHashMap<>();
        for (Object context : contexts) {
            try {
                Object factory = call(context, "getAutowireCapableBeanFactory");
                for (String name : (String[]) call(factory, "getSingletonNames")) {
                    Object singleton = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, name);
                    if (isConverter(singleton)) found.put(singleton, Boolean.TRUE);
                    else if (is(singleton, ADAPTER)) adapterConverters(singleton, found);
                }
            } catch (Throwable ignored) {
                // A closed context, or one that is not a Spring context, holds
                // no converter that serves a request.
            }
        }
        return new ArrayList<>(found.keySet());
    }

    private static void adapterConverters(Object singleton, Map<Object, Boolean> found) {
        try {
            if (!(call(singleton, "getMessageConverters") instanceof List<?> list)) return;
            for (Object converter : list) if (isConverter(converter)) found.put(converter, Boolean.TRUE);
        } catch (Throwable ignored) {
            // Not an adapter whose list can be read; it has nothing for us.
        }
    }

    static boolean isConverter(Object value) {
        return is(value, CONVERTER);
    }

    private static boolean is(Object value, String className) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(className)) return true;
        return false;
    }

    private static Map<?, ?> cache(Object converter) {
        for (Class<?> type = converter.getClass(); type != null; type = type.getSuperclass()) {
            if (!type.getName().equals(CONVERTER)) continue;
            try {
                Field field = type.getDeclaredField("jaxbContexts");
                field.setAccessible(true);
                return field.get(converter) instanceof Map<?, ?> map ? map : null;
            } catch (ReflectiveOperationException | RuntimeException absent) {
                return null;
            }
        }
        return null;
    }

    /**
     * Whether this cached context maps the changed class. The key is the
     * response type it was built for; the descriptors are every type it maps.
     * A context whose descriptors cannot be read is dropped when its key is
     * related to the change, and kept otherwise.
     */
    private static boolean covers(Object key, Object context, Class<?> changed) {
        if (key instanceof Class<?> root && changed.isAssignableFrom(root)) return true;
        try {
            Object xmlContext = call(context, "getXMLContext");
            for (Object descriptor : (List<?>) call(xmlContext, "getDescriptors")) {
                if (call(descriptor, "getJavaClass") instanceof Class<?> mapped && changed.isAssignableFrom(mapped))
                    return true;
            }
        } catch (Throwable unreadable) {
            return false;
        }
        return false;
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
