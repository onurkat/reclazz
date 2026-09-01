/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Jackson flush after an enum append, tested without Jackson.
 *
 * <p>The agent has no Jackson dependency on purpose, and this module's test
 * classpath has none either, which makes the absence case the real thing
 * rather than a simulation. The presence case compiles a stand-in
 * {@code com.fasterxml.jackson.databind.ObjectMapper} at runtime carrying the
 * three members the flush walks ({@code _serializerProvider._serializerCache}
 * with {@code flush()}, {@code _deserializationContext._cache} with
 * {@code flushCachedDeserializers()}, and the {@code _rootDeserializers}
 * map), because those names are the entire contract: a Jackson that renames
 * one must make the flush skip the mapper, and these tests hold both sides of
 * that bargain. The live measurement behind the feature is recorded in
 * {@link JacksonEnumCaches}'s javadoc; what is prevented here is the flush
 * silently doing nothing (stale caches would 500 on serialising an appended
 * constant) and the flush claiming a mapper it only half-reached.
 */
class JacksonEnumCachesTest {

    @TempDir
    Path dir;

    @AfterEach
    void unregisterContexts() {
        ApplicationContextHolder.clear();
    }

    @Test
    void withoutJacksonOrSpringTheFlushDoesNothingAndSaysNothing() {
        assertEquals(0, JacksonEnumCaches.flush(),
                "no contexts registered: an application without Spring must be untouched");

        ApplicationContextHolder.register(new Object() {
            public Map<String, Object> getBeansOfType(Class<?> type) {
                return Map.of();
            }
        });
        assertEquals(0, JacksonEnumCaches.flush(),
                "a context whose classloader has no Jackson has no cache to flush");
    }

    @Test
    void everyMapperBeanHasBothCachesFlushedAndTheRootMapCleared() throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no compiler in this JRE");

        Path mapperDir = Files.createDirectories(dir.resolve("com/fasterxml/jackson/databind"));
        Files.writeString(mapperDir.resolve("ObjectMapper.java"), """
                package com.fasterxml.jackson.databind;
                import java.util.HashMap;
                import java.util.Map;
                public class ObjectMapper {
                    public static class SerCache { public boolean flushed; public void flush() { flushed = true; } }
                    public static class SerProvider { protected SerCache _serializerCache = new SerCache(); }
                    public static class DeserCache { public boolean flushed; public void flushCachedDeserializers() { flushed = true; } }
                    public static class DeserCtx { protected DeserCache _cache = new DeserCache(); }
                    protected SerProvider _serializerProvider = new SerProvider();
                    protected DeserCtx _deserializationContext = new DeserCtx();
                    protected Map<Object, Object> _rootDeserializers = new HashMap<>(Map.of("stale", "deserializer"));
                }
                """);
        Path ctxDir = Files.createDirectories(dir.resolve("fakespring"));
        Files.writeString(ctxDir.resolve("Ctx.java"), """
                package fakespring;
                import java.util.Map;
                public class Ctx {
                    public Object mapper;
                    public Map<String, Object> getBeansOfType(Class<?> type) {
                        return type.isInstance(mapper) ? Map.of("jacksonObjectMapper", mapper) : Map.of();
                    }
                }
                """);
        int rc = compiler.run(null, null, null,
                mapperDir.resolve("ObjectMapper.java").toString(), ctxDir.resolve("Ctx.java").toString());
        assertEquals(0, rc, "the stand-in sources have to compile");

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},
                JacksonEnumCachesTest.class.getClassLoader())) {
            Object mapper = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, loader)
                    .getDeclaredConstructor().newInstance();
            Object context = Class.forName("fakespring.Ctx", true, loader)
                    .getDeclaredConstructor().newInstance();
            context.getClass().getField("mapper").set(context, mapper);
            ApplicationContextHolder.register(context);

            assertEquals(1, JacksonEnumCaches.flush(),
                    "one mapper bean, flushed once, and the same mapper via a second "
                    + "context must not be counted twice");

            Object serCache = read(read(mapper, "_serializerProvider"), "_serializerCache");
            assertTrue((boolean) serCache.getClass().getField("flushed").get(serCache),
                    "an unflushed serializer cache would 500 on serialising the new constant");
            Object deserCache = read(read(mapper, "_deserializationContext"), "_cache");
            assertTrue((boolean) deserCache.getClass().getField("flushed").get(deserCache),
                    "an unflushed deserializer cache would refuse the new constant's name");
            assertTrue(((Map<?, ?>) read(mapper, "_rootDeserializers")).isEmpty(),
                    "a stale root deserializer would keep serving the old enum universe");
        }
    }

    /**
     * A mapper missing one of the walked members must not be counted, or the
     * report would say Jackson sees the new constant while one direction is
     * still stale.
     */
    @Test
    void aMapperWhoseCacheFieldsAreMissingIsSkippedNotHalfFlushed() throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no compiler in this JRE");

        Path mapperDir = Files.createDirectories(dir.resolve("com/fasterxml/jackson/databind"));
        Files.writeString(mapperDir.resolve("ObjectMapper.java"), """
                package com.fasterxml.jackson.databind;
                public class ObjectMapper {
                    public static class SerCache { public boolean flushed; public void flush() { flushed = true; } }
                    public static class SerProvider { protected SerCache _serializerCache = new SerCache(); }
                    protected SerProvider _serializerProvider = new SerProvider();
                    // no _deserializationContext, no _rootDeserializers
                }
                """);
        Path ctxDir = Files.createDirectories(dir.resolve("fakespring"));
        Files.writeString(ctxDir.resolve("Ctx.java"), """
                package fakespring;
                import java.util.Map;
                public class Ctx {
                    public Object mapper;
                    public Map<String, Object> getBeansOfType(Class<?> type) {
                        return type.isInstance(mapper) ? Map.of("jacksonObjectMapper", mapper) : Map.of();
                    }
                }
                """);
        int rc = compiler.run(null, null, null,
                mapperDir.resolve("ObjectMapper.java").toString(), ctxDir.resolve("Ctx.java").toString());
        assertEquals(0, rc);

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},
                JacksonEnumCachesTest.class.getClassLoader())) {
            Object mapper = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, loader)
                    .getDeclaredConstructor().newInstance();
            Object context = Class.forName("fakespring.Ctx", true, loader)
                    .getDeclaredConstructor().newInstance();
            context.getClass().getField("mapper").set(context, mapper);
            ApplicationContextHolder.register(context);

            assertEquals(0, JacksonEnumCaches.flush());
            Object serCache = read(read(mapper, "_serializerProvider"), "_serializerCache");
            assertFalse((boolean) serCache.getClass().getField("flushed").get(serCache),
                    "everything is located before anything is touched: a mapper that "
                    + "cannot be fully flushed must not be partly flushed");
        }
    }

    private static Object read(Object target, String fieldName) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException next) {
                // keep walking
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
