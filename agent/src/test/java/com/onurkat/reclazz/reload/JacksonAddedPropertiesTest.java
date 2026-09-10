/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.fasterxml.jackson.annotation.*;
import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.transform.JacksonAccessorTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JacksonAddedPropertiesTest {
    public static class Owner {
        public Object[] __reclazz$ext;
        private Object value;
        private int calls;
        public void receive(int number) { value = number; calls++; }
        public void receive(String text) { value = text; calls++; }
        public void receive(List<?> values) { value = values; calls++; }
        public void fail(String value) { throw new IllegalStateException("setter-failed"); }
    }
    public static class Setters {
        @JsonProperty("number") public void setNumber(int value) { }
        @JsonSetter(value="label", nulls=Nulls.SKIP) @JsonAlias("old_label") private void accept(String value) { }
        public void setValues(List<Integer> value) { }
        public void setFailure(String value) { }
    }
    public static class Fields {
        @JsonProperty("display") @JsonAlias("old_name") private String name;
        public List<Integer> values;
        public Map<String, List<Integer>> nested;
        @JsonSetter(nulls=Nulls.SKIP) public String note;
        @JsonIgnore public String secret;
        @JsonProperty(access=JsonProperty.Access.READ_ONLY) public String output;
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String input;
        public int count;
        public static String staticField;
        public transient String transientField;
    }
    public static class PrivateField { @JsonProperty private String hidden; }
    public static class FinalField { @JsonProperty public final int fixed = 3; }
    public static class RenamedField { @JsonProperty("renamed") public String name; }
    public static class Empty { }
    public abstract static class FieldMixIn { @JsonProperty("mixin_name") public String name; }
    public static class PrimitiveFields {
        public boolean bool; public byte small; public short medium; public char letter;
        public int integer; public long large; public float single; public double decimal;
        public String text;
    }
    public static class SkippedSetters {
        public static void setStatic(String value) { }
        public Object setFluent(String value) { return this; }
        public void setTwo(String a, String b) { }
        public void unrelated(String value) { }
    }

    @AfterEach void clear() { JacksonBridge.replace(Owner.class, null, Map.of()); }

    @Test void setterMetadataUsesAliasNullPolicyAndGenericConversion() throws Exception {
        publish(Setters.class);
        Object mapper = mapper();
        Owner number = read(mapper, "{\"number\":\"12\"}");
        assertEquals(12, number.value); assertEquals(1, number.calls);
        Owner label = read(mapper, "{\"old_label\":\"text\",\"label\":null}");
        assertEquals("text", label.value); assertEquals(1, label.calls);
        Owner values = read(mapper, "{\"values\":[1,2]}");
        assertEquals(List.of(1, 2), values.value);
        assertInstanceOf(Integer.class, ((List<?>) values.value).get(0));
        assertEquals("java.util.List<java.lang.Integer>", method("setValues").getGenericParameterTypes()[0].getTypeName());
        assertThrows(NoSuchMethodException.class, () -> Owner.class.getDeclaredMethod("setNumber", int.class));
    }

    @Test void invalidConversionAndSetterExceptionKeepJacksonErrors() throws Exception {
        publish(Setters.class);
        InvocationTargetException invalid = assertThrows(InvocationTargetException.class,
                () -> read(mapper(), "{\"number\":\"wrong\"}"));
        assertTrue(invalid.getCause().getMessage().contains("number"));
        InvocationTargetException throwing = assertThrows(InvocationTargetException.class,
                () -> read(mapper(), "{\"failure\":\"throw\"}"));
        assertTrue(throwing.getCause().getMessage().contains("failure"));
        Throwable root = throwing;
        while (root.getCause() != null) root = root.getCause();
        assertInstanceOf(IllegalStateException.class, root);
        assertEquals("setter-failed", root.getMessage());
    }

    @Test void fieldsUseTheRealStorageAndPreserveJsonAccessPolicies() throws Exception {
        publish(Fields.class);
        Object mapper = mapper();
        Owner owner = read(mapper, "{\"old_name\":\"hello\",\"values\":[1,2],\"nested\":{\"a\":[3]},"
                + "\"secret\":\"hidden\",\"input\":\"in\",\"output\":\"blocked\",\"count\":4}");
        assertEquals("hello", FieldStore.getExtField(owner, Owner.class.getName(), "name", "Ljava/lang/String;"));
        assertEquals(4, FieldStore.getExtField(owner, Owner.class.getName(), "count", "I"));
        assertNull(FieldStore.getExtField(owner, Owner.class.getName(), "secret", "Ljava/lang/String;"));
        assertNull(FieldStore.getExtField(owner, Owner.class.getName(), "output", "Ljava/lang/String;"));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(write(mapper, owner));
        assertEquals("hello", tree.path("display").asText());
        assertEquals(3, tree.path("nested").path("a").get(0).asInt());
        assertFalse(tree.has("input")); assertFalse(tree.has("secret"));
        assertFalse(tree.has("staticField")); assertFalse(tree.has("transientField"));
        assertFalse(tree.has("__reclazz$ext"));
        assertThrows(NoSuchFieldException.class, () -> Owner.class.getDeclaredField("name"));
        assertEquals(Owner.class, JacksonBridge.getDeclaringClass(field("name")));
        assertEquals(Owner.class, JacksonBridge.getDeclaringClass((Member) field("name")));
    }

    @Test void fieldNullsSkipAndExplicitNullDifferOnExistingObject() throws Exception {
        publish(Fields.class);
        Object mapper = mapper();
        Owner owner = read(mapper, "{\"note\":\"keep\",\"display\":\"clear\"}");
        Object reader = mapper.getClass().getMethod("readerForUpdating", Object.class).invoke(mapper, owner);
        assertSame(owner, reader.getClass().getMethod("readValue", String.class).invoke(reader,
                "{\"note\":null,\"display\":null}"));
        assertEquals("keep", JacksonBridge.get(field("note"), owner));
        Field name = field("name"); name.setAccessible(true);
        assertNull(JacksonBridge.get(name, owner));
    }

    @Test void fieldMixinNamingAndRenameRemovalRestorationUseFreshDiscovery() throws Exception {
        publish(RenamedField.class);
        Object mapper = mapper();
        mapper.getClass().getMethod("addMixIn", Class.class, Class.class).invoke(mapper, Owner.class, FieldMixIn.class);
        Owner owner = read(mapper, "{\"mixin_name\":\"mixed\"}");
        assertTrue(write(mapper, owner).contains("\"mixin_name\":\"mixed\""));
        publish(Empty.class);
        assertFalse(JacksonBridge.hasGetters(Owner.class));
        assertThrows(InvocationTargetException.class, () -> read(mapper(), "{\"renamed\":\"gone\"}"));
        publish(RenamedField.class);
        assertTrue(write(mapper(), owner).contains("\"renamed\":\"mixed\""));
    }

    @Test void privateSettersAndFieldsRespectEachMappersAccessPolicy() throws Exception {
        for (Class<?> shape : List.of(Setters.class, PrivateField.class)) {
            publish(shape);
            String input = shape == Setters.class ? "{\"label\":\"yes\"}" : "{\"hidden\":\"yes\"}";
            read(mapper(), input); // A different mapper enabling access must not grant it globally.
            Object restricted = mapper();
            Class<?> feature = restricted.getClass().getClassLoader().loadClass("com.fasterxml.jackson.databind.MapperFeature");
            restricted.getClass().getMethod("configure", feature, boolean.class).invoke(restricted,
                    feature.getField("CAN_OVERRIDE_ACCESS_MODIFIERS").get(null), false);
            Throwable failure = assertThrows(InvocationTargetException.class, () -> read(restricted, input));
            while (failure.getCause() != null) failure = failure.getCause();
            assertInstanceOf(IllegalAccessException.class, failure);
        }
    }

    @Test void finalAddedFieldsNeverBypassTheReflectionWriteRestriction() throws Exception {
        publish(FinalField.class);
        Field field = field("fixed"); field.setAccessible(true);
        assertThrows(IllegalAccessException.class, () -> JacksonBridge.set(field, new Owner(), 8));
        assertThrows(InvocationTargetException.class, () -> read(mapper(), "{\"fixed\":8}"));
    }

    @Test void primitiveFieldWritesMatchReflectionWideningAndFailures() throws Exception {
        publish(PrimitiveFields.class);
        Object[] values = {null, true, (byte) 1, (short) 2, 'a', 3, 4L, 5F, 6D, "text", new Object()};
        for (Field real : PrimitiveFields.class.getDeclaredFields()) {
            Field added = field(real.getName());
            for (Object value : values) {
                var control = new PrimitiveFields(); var owner = new Owner();
                try { real.set(control, value); }
                catch (IllegalArgumentException expected) {
                    assertThrows(IllegalArgumentException.class, () -> JacksonBridge.set(added, owner, value), real + " <- " + value);
                    continue;
                }
                JacksonBridge.set(added, owner, value);
                assertEquals(real.get(control), JacksonBridge.get(added, owner), real + " <- " + value);
            }
        }
    }

    @Test void setterArgumentValidationHappensOutsideTheInvocationExceptionBoundary() throws Exception {
        publish(Setters.class);
        var owner = new Owner();
        Method number = method("setNumber");
        JacksonBridge.invoke(number, owner, new Object[]{(byte) 9});
        assertEquals(9, owner.value); assertEquals(1, owner.calls);
        for (Object invalid : new Object[]{null, 9L, "9"})
            assertThrows(IllegalArgumentException.class, () -> JacksonBridge.invoke(number, owner, new Object[]{invalid}));
        assertEquals(1, owner.calls);
        assertThrows(IllegalArgumentException.class, () -> JacksonBridge.invoke(number, owner, null));
        assertThrows(IllegalArgumentException.class, () -> JacksonBridge.invoke(number, new Object(), new Object[]{1}));
        assertThrows(NullPointerException.class, () -> JacksonBridge.invoke(number, null, new Object[]{1}));
    }

    @Test void normalFieldReflectionIsDelegatedAndInvalidVirtualReceiversFail() throws Exception {
        var real = PrimitiveFields.class.getDeclaredField("integer"); var value = new PrimitiveFields();
        JacksonBridge.set(real, value, 12); assertEquals(12, JacksonBridge.get(real, value));
        publish(Fields.class); Field added = field("count");
        assertThrows(NullPointerException.class, () -> JacksonBridge.get(added, null));
        assertThrows(IllegalArgumentException.class, () -> JacksonBridge.set(added, new Object(), 1));
        assertEquals(PrimitiveFields.class, JacksonBridge.getDeclaringClass(real));
    }

    @Test void unsupportedSetterShapesAreNotExposed() throws Exception {
        publish(SkippedSetters.class);
        assertFalse(JacksonBridge.hasGetters(Owner.class));
    }

    private static void publish(Class<?> shape) throws Exception {
        Map<String, MethodHandle> targets = new LinkedHashMap<>();
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup());
        for (Method method : shape.getDeclaredMethods()) {
            if (method.getParameterCount() != 1 || method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) continue;
            String targetName = method.getName().equals("setFailure") ? "fail" : "receive";
            if (shape == SkippedSetters.class) continue;
            MethodHandle target = lookup.findVirtual(Owner.class, targetName, MethodType.methodType(void.class, method.getParameterTypes()));
            targets.put(InjectedNames.siteKey(method.getName(), InjectedNames.descHash(Type.getMethodDescriptor(method))), target);
        }
        DispatchTable.retargetAll(Owner.class, lookup, targets);
        JacksonAddedGetters.publish(Owner.class, bytes(shape), lookup, targets);
    }

    private static Field field(String name) {
        return Arrays.stream(JacksonBridge.getDeclaredFields(Owner.class)).filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }
    private static Method method(String name) {
        return Arrays.stream(JacksonBridge.getDeclaredMethods(Owner.class)).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }
    private static byte[] bytes(Class<?> type) throws IOException {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return Objects.requireNonNull(input).readAllBytes();
        }
    }
    private static Owner read(Object mapper, String input) throws Exception {
        return (Owner) mapper.getClass().getMethod("readValue", String.class, Class.class).invoke(mapper, input, Owner.class);
    }
    private static String write(Object mapper, Owner value) throws Exception {
        return (String) mapper.getClass().getMethod("writeValueAsString", Object.class).invoke(mapper, value);
    }
    private static Object mapper() throws Exception {
        ClassLoader loader = new ClassLoader(JacksonAddedPropertiesTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (!name.startsWith("com.fasterxml.jackson.databind.")) return super.loadClass(name, resolve);
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        String internal = name.replace('.', '/');
                        try (var input = getParent().getResourceAsStream(internal + ".class")) {
                            byte[] original = Objects.requireNonNull(input).readAllBytes();
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
