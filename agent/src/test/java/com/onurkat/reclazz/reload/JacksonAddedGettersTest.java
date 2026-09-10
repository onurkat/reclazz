/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.fasterxml.jackson.annotation.*;
import com.onurkat.reclazz.bootstrap.DispatchTable;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.bootstrap.JacksonBridge;
import com.onurkat.reclazz.transform.JacksonAccessorTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JacksonAddedGettersTest {
    public static class Owner { public String getName() { return "original"; } }
    public static class Shape {
        public String getAdded() { return null; }
        @JsonIgnore public String getSecret() { return null; }
        @JsonProperty("private_name") private String named() { return null; }
    }
    public static class Renamed {
        @JsonProperty("renamed") public String getAdded() { return null; }
    }
    public static class Empty { }
    public static class Skipped {
        public static String getStatic() { return null; }
        public String getParameter(String ignored) { return null; }
        public void getVoid() { }
        public String helper() { return null; }
    }
    public static class Generic<T extends CharSequence> {
        public List<T> getValues() { return null; }
    }
    public abstract static class MixIn {
        @JsonProperty("from_mixin") abstract String getAdded();
    }
    public static class Throwing { public String getFailure() { return null; } }
    public static class AnnotatedField {
        @JsonIgnore private String secret;
        public String getSecret() { return secret; }
    }

    @AfterEach void clearMetadata() { JacksonBridge.replace(Owner.class, null, Map.of()); }

    @Test void realJacksonDiscoversMetadataAndInvokesTheOriginalReceiver() throws Exception {
        publish(Shape.class, Map.of("getAdded", "live", "getSecret", "hidden", "named", "private"));
        Object mapper = mapper();
        String json = json(mapper);
        assertEquals(Map.of("name", "original", "added", "live", "private_name", "private"),
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class));
        assertThrows(NoSuchMethodException.class, () -> Owner.class.getDeclaredMethod("getAdded"));
        assertEquals(Owner.class, JacksonBridge.getDeclaringClass(getter("getAdded")));
    }

    @Test void mixInsAreAppliedByJacksonToTheAddedMethod() throws Exception {
        publish(Renamed.class, Map.of("getAdded", "value"));
        Object mapper = mapper();
        mapper.getClass().getMethod("addMixIn", Class.class, Class.class).invoke(mapper, Owner.class, MixIn.class);
        assertEquals(Map.of("name", "original", "from_mixin", "value"),
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(json(mapper), Map.class));
    }

    @Test void retainedWriterUsesNewBodiesWhileNewDiscoveryUsesNewMetadata() throws Exception {
        publish(Renamed.class, Map.of("getAdded", "one"));
        Object mapper = mapper();
        Object writer = mapper.getClass().getMethod("writerFor", Class.class).invoke(mapper, Owner.class);
        assertTrue(write(writer).contains("\"renamed\":\"one\""));
        publish(Renamed.class, Map.of("getAdded", "two"));
        assertTrue(write(writer).contains("\"renamed\":\"two\""));
        publish(Empty.class, Map.of());
        assertFalse(JacksonBridge.hasGetters(Owner.class));
        assertEquals("{\"name\":\"original\"}", json(mapper()));
        publish(Renamed.class, Map.of("getAdded", "three"));
        assertTrue(json(mapper()).contains("\"renamed\":\"three\""));
    }

    @Test void getterFailuresKeepJacksonMappingPathAndRootCause() throws Exception {
        publish(Throwing.class, Map.of());
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> json(mapper()));
        assertTrue(failure.getCause().getMessage().contains("failure"), failure.toString());
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertInstanceOf(IllegalStateException.class, root);
        assertEquals("getter-failed", root.getMessage());
    }

    @Test void privateGetterRespectsDisabledAccessOverride() throws Exception {
        publish(Shape.class, Map.of("getAdded", "live", "getSecret", "hidden", "named", "private"));
        assertTrue(json(mapper()).contains("\"private_name\":\"private\""), "first mapper enables private access");
        Object mapper = mapper();
        Class<?> features = mapper.getClass().getClassLoader().loadClass("com.fasterxml.jackson.databind.MapperFeature");
        Object access = features.getField("CAN_OVERRIDE_ACCESS_MODIFIERS").get(null);
        mapper.getClass().getMethod("configure", features, boolean.class).invoke(mapper, access, false);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> json(mapper));
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertInstanceOf(IllegalAccessException.class, root);
    }

    @Test void genericGetterRetainsTypeParametersAndSerializesValues() throws Exception {
        publish(Generic.class, Map.of("getValues", List.of("a", "b")));
        assertEquals("java.util.List<T>", getter("getValues").getGenericReturnType().getTypeName());
        assertTrue(json(mapper()).contains("\"values\":[\"a\",\"b\"]"));
    }

    @Test void nonGetterShapesAreNotPublished() throws Exception {
        assertTrue(publish(Skipped.class, Map.of()).isEmpty());
        assertFalse(JacksonBridge.hasGetters(Owner.class));
        assertEquals("{\"name\":\"original\"}", json(mapper()));
    }

    @Test void addedFieldAnnotationsParticipateInGetterDiscovery() throws Exception {
        publish(Shape.class, Map.of("getAdded", "old", "getSecret", "hidden", "named", "private"));
        publish(AnnotatedField.class, Map.of("getSecret", "must-not-leak"));
        assertTrue(JacksonBridge.hasGetters(Owner.class));
        assertTrue(Arrays.stream(JacksonBridge.getDeclaredFields(Owner.class))
                .anyMatch(f -> f.getName().equals("secret") && f.isAnnotationPresent(JsonIgnore.class)));
        assertEquals("{\"name\":\"original\"}", json(mapper()));
    }

    @Test void normalReflectionInvocationAndInvalidBridgeCallsKeepTheirContract() throws Exception {
        Method original = Owner.class.getDeclaredMethod("getName");
        assertEquals("original", JacksonBridge.invoke(original, new Owner(), null));
        publish(Renamed.class, Map.of("getAdded", "value"));
        Method added = getter("getAdded");
        assertThrows(NullPointerException.class, () -> JacksonBridge.invoke(added, null, null));
        assertThrows(IllegalArgumentException.class, () -> JacksonBridge.invoke(added, new Object(), null));
        assertThrows(IllegalArgumentException.class, () -> JacksonBridge.invoke(added, new Owner(), new Object[]{1}));
    }

    @Test void transformerLeavesApplicationCodeAlone() throws Exception {
        var transformer = new JacksonAccessorTransformer();
        assertNull(transformer.transform(getClass().getClassLoader(), "app/Dto", null, null, bytes(Owner.class)));
        assertNull(transformer.transform(getClass().getClassLoader(), null, null, null, bytes(Owner.class)));
    }

    private static Method getter(String name) {
        return Arrays.stream(JacksonBridge.getDeclaredMethods(Owner.class)).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Set<String> publish(Class<?> shape, Map<String, Object> values) throws Exception {
        Map<String, MethodHandle> targets = new LinkedHashMap<>();
        for (Method method : shape.getDeclaredMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() == void.class) continue;
            MethodHandle target;
            if (method.getName().equals("getFailure")) {
                target = MethodHandles.lookup().findStatic(JacksonAddedGettersTest.class, "fail",
                        MethodType.methodType(String.class, Owner.class));
            } else {
                target = MethodHandles.dropArguments(MethodHandles.constant(method.getReturnType(), values.get(method.getName())), 0, Owner.class);
            }
            targets.put(InjectedNames.siteKey(method.getName(), InjectedNames.descHash(Type.getMethodDescriptor(method))), target);
        }
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup());
        DispatchTable.retargetAll(Owner.class, lookup, targets);
        return JacksonAddedGetters.publish(Owner.class, bytes(shape), lookup, targets);
    }

    private static String fail(Owner owner) { throw new IllegalStateException("getter-failed"); }
    private static byte[] bytes(Class<?> type) throws IOException {
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return Objects.requireNonNull(stream).readAllBytes();
        }
    }
    private static String json(Object mapper) throws Exception { return write(mapper); }
    private static String write(Object mapperOrWriter) throws Exception {
        return (String) mapperOrWriter.getClass().getMethod("writeValueAsString", Object.class).invoke(mapperOrWriter, new Owner());
    }

    /** Isolate transformed Jackson from the runner and unrelated tests. No stand-in mapper. */
    private static Object mapper() throws Exception {
        ClassLoader loader = new ClassLoader(JacksonAddedGettersTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (!name.startsWith("com.fasterxml.jackson.databind.")) return super.loadClass(name, resolve);
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        String internal = name.replace('.', '/');
                        try (var stream = getParent().getResourceAsStream(internal + ".class")) {
                            byte[] original = Objects.requireNonNull(stream).readAllBytes();
                            byte[] transformed = new JacksonAccessorTransformer().transform(this, internal, null, null, original);
                            byte[] result = transformed == null ? original : transformed;
                            type = defineClass(name, result, 0, result.length);
                        } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        return loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper").getConstructor().newInstance();
    }
}
