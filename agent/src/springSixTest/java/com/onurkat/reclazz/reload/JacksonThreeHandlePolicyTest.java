/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.onurkat.reclazz.bootstrap.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.Type;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JacksonThreeHandlePolicyTest {
    private static final IllegalStateException FAILURE = new IllegalStateException("setter-target");
    public static class Owner {
        public Object[] __reclazz$ext;
        int value;
        public void receive(int number) { value = number; }
        public void explode(String text) { throw FAILURE; }
    }
    static class HiddenOwner {
        public Object[] __reclazz$ext;
        int value;
        public void receive(int number) { value = number; }
        public void explode(String text) { throw FAILURE; }
    }
    public static class Shape {
        public int count;
        @JsonProperty private int hidden;
        @JsonProperty public final int fixed = 3;
        public void setNumber(int value) { }
        @JsonProperty private void setPrivate(int value) { }
        public void setFailure(String value) { }
    }
    public static class Plain {
        public int count;
        public void setNumber(int value) { count = value; }
        public void setFailure(String value) { throw FAILURE; }
    }
    @BeforeEach void version() throws Exception {
        String actual;
        try { actual = Class.forName("tools.jackson.databind.cfg.PackageVersion").getField("VERSION").get(null).toString(); }
        catch (ClassNotFoundException absent) { Assumptions.assumeTrue(false, "Jackson3 isolated runtime required"); return; }
        String expected = System.getProperty("reclazz.jackson3.expectedVersion");
        if (expected != null) assertEquals(expected, actual, "isolated Jackson runtime");
    }
    @AfterEach void clear() {
        JacksonBridge.replace(Owner.class, null, Map.of()); JacksonBridge.replace(HiddenOwner.class, null, Map.of());
    }
    private static void publish(Class<?> owner) throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
        Map<String, MethodHandle> targets = new LinkedHashMap<>();
        for (Method method : Shape.class.getDeclaredMethods()) {
            if (method.getParameterCount() != 1 || method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) continue;
            String target = method.getName().equals("setFailure") ? "explode" : "receive";
            targets.put(InjectedNames.siteKey(method.getName(), InjectedNames.descHash(Type.getMethodDescriptor(method))),
                    lookup.findVirtual(owner, target, MethodType.methodType(void.class, method.getParameterTypes())));
        }
        DispatchTable.retargetAll(owner, lookup, targets);
        try (var input = Shape.class.getResourceAsStream("/" + Shape.class.getName().replace('.', '/') + ".class")) {
            JacksonAddedGetters.publish(owner, Objects.requireNonNull(input).readAllBytes(), lookup, targets);
        }
    }
    private static Field field(Class<?> owner, String name) {
        return Arrays.stream(JacksonBridge.getDeclaredFields(owner)).filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }
    private static Method method(Class<?> owner, String name) {
        return Arrays.stream(JacksonBridge.getDeclaredMethods(owner)).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }
    // Reflection permits a meaningful BASE-red run before the new public entry points exist.
    private static MethodHandle fieldHandle(String name, Field field) throws Throwable {
        try { return (MethodHandle) JacksonBridge.class.getMethod(name, MethodHandles.Lookup.class, Field.class)
                .invoke(null, MethodHandles.lookup(), field); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    @Test void publicMemberOnNonPublicOwnerRequiresExplicitAccessForFieldsAndSetters() throws Throwable {
        publish(HiddenOwner.class); var bean = new HiddenOwner(); Field count = field(HiddenOwner.class, "count");
        assertThrows(IllegalAccessException.class, () -> fieldHandle("unreflectGetter", count));
        assertThrows(IllegalAccessException.class, () -> fieldHandle("unreflectSetter", count));
        Method setter = method(HiddenOwner.class, "setNumber");
        assertThrows(IllegalAccessException.class, () -> JacksonBridge.unreflect(MethodHandles.lookup(), setter));
        count.setAccessible(true); fieldHandle("unreflectSetter", count).invokeWithArguments(bean, 11);
        assertEquals(11, fieldHandle("unreflectGetter", count).invokeWithArguments(bean));
        setter.setAccessible(true); JacksonBridge.unreflect(MethodHandles.lookup(), setter).invokeWithArguments(bean, 13);
        assertEquals(13, bean.value);
    }
    @Test void privateMembersRequireExplicitAccessEvenWhenLookupCanSeeTheCarrier() throws Throwable {
        publish(Owner.class); var bean = new Owner(); Field hidden = field(Owner.class, "hidden");
        assertThrows(IllegalAccessException.class, () -> fieldHandle("unreflectGetter", hidden));
        Method setter = method(Owner.class, "setPrivate");
        assertThrows(IllegalAccessException.class, () -> JacksonBridge.unreflect(MethodHandles.lookup(), setter));
        hidden.setAccessible(true); fieldHandle("unreflectSetter", hidden).invokeWithArguments(bean, 17);
        assertEquals(17, fieldHandle("unreflectGetter", hidden).invokeWithArguments(bean));
        setter.setAccessible(true); JacksonBridge.unreflect(MethodHandles.lookup(), setter).invokeWithArguments(bean, 19);
        assertEquals(19, bean.value);
    }
    @Test void finalFieldSetterIsStillForbiddenAfterAccessIsEnabled() throws Throwable {
        publish(Owner.class); Field fixed = field(Owner.class, "fixed"); fixed.setAccessible(true);
        assertThrows(IllegalAccessException.class, () -> fieldHandle("unreflectSetter", fixed));
    }
    @Test void primitiveNullAndTypeFailuresMatchNativeHandleBoundaries() throws Throwable {
        publish(Owner.class); var bean = new Owner(); Field count = field(Owner.class, "count");
        var fieldSetter = fieldHandle("unreflectSetter", count);
        var setter = JacksonBridge.unreflect(MethodHandles.lookup(), method(Owner.class, "setNumber"));
        for (MethodHandle handle : List.of(fieldSetter, setter)) {
            assertThrows(NullPointerException.class, () -> handle.invokeWithArguments(new Object[]{bean, null}));
            assertThrows(ClassCastException.class, () -> handle.invokeWithArguments(bean, "wrong"));
            handle.invokeWithArguments(bean, (byte) 5);
        }
        assertEquals(5, bean.value); assertEquals(5, fieldHandle("unreflectGetter", count).invokeWithArguments(bean));
    }
    @Test void nonSchemaFieldsAndMethodsKeepNativeResultsAndExceptions() throws Throwable {
        var bean = new Plain(); Field field = Plain.class.getField("count");
        var setter = fieldHandle("unreflectSetter", field); var getter = fieldHandle("unreflectGetter", field);
        setter.invokeWithArguments(bean, 23); assertEquals(23, getter.invokeWithArguments(bean));
        assertEquals(MethodHandles.lookup().unreflectGetter(field).type(), getter.type());
        assertThrows(NullPointerException.class, () -> setter.invokeWithArguments(new Object[]{bean, null}));
        assertThrows(ClassCastException.class, () -> setter.invokeWithArguments(bean, "wrong"));
        Method method = Plain.class.getMethod("setNumber", int.class);
        JacksonBridge.unreflect(MethodHandles.lookup(), method).invokeWithArguments(bean, 29); assertEquals(29, bean.count);
        var throwing = JacksonBridge.unreflect(MethodHandles.lookup(), Plain.class.getMethod("setFailure", String.class));
        assertSame(FAILURE, assertThrows(IllegalStateException.class, () -> throwing.invokeWithArguments(bean, "x")));
    }
    @Test void throwingAddedSetterKeepsTheTargetExceptionWithoutReflectionWrapping() throws Throwable {
        publish(Owner.class); var handle = JacksonBridge.unreflect(MethodHandles.lookup(), method(Owner.class, "setFailure"));
        assertSame(FAILURE, assertThrows(IllegalStateException.class, () -> handle.invokeWithArguments(new Owner(), "x")));
    }
}
