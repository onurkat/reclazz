/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.platform.ApplicationContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Flushes Jackson's per-mapper enum caches after an enum append, so JSON
 * carries the new constant instead of failing on it.
 *
 * <p>Jackson builds an enum's serializer and deserializer once per
 * ObjectMapper and keeps them: the serializer holds an array of names indexed
 * by ordinal, the deserializer a resolver of the names it saw. Both are sized
 * to the constants that existed at first use, and an append does not touch
 * them. Measured on Spring Boot 3.3.4 (Jackson via spring-boot-starter-web,
 * stock JDK 21) after appending URGENT to a two-constant enum whose endpoints
 * had already served traffic:
 *
 * <pre>
 *   serialise the new constant    HTTP 500, HttpMessageNotWritableException:
 *                                 Could not write JSON: Index 2 out of bounds for length 2
 *   deserialise its name          HTTP 400, Cannot deserialize value of type `demo.Priority`
 *                                 from String "URGENT": not one of the values accepted
 *                                 for Enum class: [HIGH, LOW]
 *   old constants, both ways      fine
 * </pre>
 *
 * <p>The repair is to flush the two caches and the root-deserializer map on
 * every ObjectMapper registered as a Spring bean, all through reflection:
 * Reclazz has no Jackson dependency and must behave identically when Jackson
 * is absent. Everything else those caches held rebuilds lazily on next use,
 * which is the trade a development reload is allowed to make. Mappers that
 * are not Spring beans cannot be found and stay stale; the fields walked here
 * ({@code _serializerProvider._serializerCache}, {@code
 * _deserializationContext._cache}, {@code _rootDeserializers}) have been
 * stable across Jackson 2.x, and a Jackson that renames one makes this skip
 * that mapper silently rather than half-flush and misreport.
 */
final class JacksonEnumCaches {

    private JacksonEnumCaches() {
    }

    /**
     * Flush every ObjectMapper bean in every live ApplicationContext.
     *
     * @return how many mappers were flushed; 0 when Jackson or Spring is absent
     */
    static int flushAfterAppend() {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        int flushed = 0;
        for (Object context : ApplicationContextHolder.getAllContexts()) {
            try {
                Class<?> mapperType = Class.forName("com.fasterxml.jackson.databind.ObjectMapper",
                        false, context.getClass().getClassLoader());
                Method getBeansOfType = context.getClass().getMethod("getBeansOfType", Class.class);
                Object beans = getBeansOfType.invoke(context, mapperType);
                if (!(beans instanceof Map<?, ?> map)) continue;
                for (Object mapper : map.values()) {
                    if (mapper == null || seen.put(mapper, Boolean.TRUE) != null) continue;
                    if (flushMapper(mapper)) flushed++;
                }
            } catch (Throwable ignored) {
                // A context without Jackson, or one that will not be asked,
                // has no cache to go stale.
            }
        }
        return flushed;
    }

    /**
     * Both directions and the root map, or it does not count. A mapper whose
     * serializer cache flushed but whose deserializer cache could not be found
     * would make "Jackson sees the new constant" half true, which is the kind
     * of report this codebase refuses to make.
     */
    private static boolean flushMapper(Object mapper) {
        try {
            Object provider = read(mapper, "_serializerProvider");
            Object serializerCache = read(provider, "_serializerCache");
            Method flush = serializerCache.getClass().getMethod("flush");
            flush.setAccessible(true);

            Object deserializationContext = read(mapper, "_deserializationContext");
            Object deserializerCache = read(deserializationContext, "_cache");
            Method flushDeser = deserializerCache.getClass().getMethod("flushCachedDeserializers");
            flushDeser.setAccessible(true);

            Object rootDeserializers = read(mapper, "_rootDeserializers");
            if (!(rootDeserializers instanceof Map<?, ?> roots)) return false;

            // Everything was located; only now is anything touched.
            flush.invoke(serializerCache);
            flushDeser.invoke(deserializerCache);
            roots.clear();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object read(Object target, String fieldName) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value == null) throw new IllegalStateException(fieldName + " is null");
                return value;
            } catch (NoSuchFieldException next) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(fieldName + " not found on " + target.getClass().getName());
    }
}
