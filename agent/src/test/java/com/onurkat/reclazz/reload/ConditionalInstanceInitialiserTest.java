/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.transform.TransformContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConditionalInstanceInitialiserTest {
    record Box(int number) { }
    static class Fixture {
        static int constructors;
        private Object[] __reclazz$ext;
        boolean enabled, secondary;
        int leftCalls, rightCalls, conditionCalls;
        int existing = 17;
        final Object leftValue = new Object(), rightValue = new Object();
        { if (enabled) existing++; }
        Object choice = condition() ? left() : right();
        Object nullable = enabled ? leftValue : null;
        long wide = enabled ? 123456789012L : -7L;
        double decimal = enabled ? 2.5 : -3.25;
        boolean active = enabled && secondary;
        int nested = enabled ? (secondary ? 7 : 8) : 9;
        Box boxed = new Box(enabled ? 11 : 12);
        Fixture(boolean enabled) {
            this.enabled = enabled;
            secondary = true;
            constructors++;
            leftCalls = rightCalls = conditionCalls = 0;
        }
        private boolean condition() { conditionCalls++; return enabled; }
        private Object left() { leftCalls++; return leftValue; }
        private Object right() { rightCalls++; return rightValue; }
    }
    static class Parameter {
        String value;
        Parameter(boolean flag) { value = flag ? "a" : "b"; }
    }
    static class Guarded {
        boolean flag;
        String value;
        Guarded() { if (flag) value = "a"; }
    }
    static class Outer {
        boolean flag, other;
        String value;
        Outer() { if (flag) value = other ? "a" : "b"; }
    }
    static class Loop {
        boolean flag;
        String value;
        Loop() { do { value = flag ? "a" : "b"; } while (flag); }
    }
    static class Multiple {
        boolean flag;
        String value = flag ? "a" : "b";
        Multiple() { value = "c"; }
    }
    static class Constructors {
        boolean flag;
        String value = flag ? "a" : "b";
        Constructors() { }
        Constructors(boolean flag) { this.flag = flag; }
    }
    static class Shared {
        boolean flag;
        String other;
        String value = flag ? (other = "a") : "b";
    }
    static class StaticWrite {
        boolean flag;
        static String other;
        String value = flag ? (other = "a") : "b";
    }
    static class Catching {
        boolean flag;
        String value;
        Catching() { try { value = flag ? "a" : "b"; } catch (RuntimeException e) { flag = false; } }
    }
    static class Switching {
        int flag;
        String value = switch (flag) { case 1 -> "a"; case 4 -> "b"; default -> "c"; };
    }
    static class ForeignReceiver {
        boolean flag;
        ForeignReceiver other;
        String value;
        ForeignReceiver() { this.other.value = flag ? "a" : "b"; }
    }
    static class Local {
        boolean flag;
        String value;
        Local() { String local = "x"; value = flag ? local : "y"; }
    }
    static class ArrayWrite {
        boolean flag;
        String[] other = new String[1];
        String value = flag ? (other[0] = "a") : "b";
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void currentObjectStateChoosesTheBranch(boolean enabled) throws Throwable {
        Fixture object = new Fixture(enabled);
        object.existing = 99;
        int constructors = Fixture.constructors;
        register(Set.of("choice:Ljava/lang/Object;", "nullable:Ljava/lang/Object;", "wide:J", "decimal:D",
                "active:Z", "nested:I", "boxed:Lcom/onurkat/reclazz/reload/ConditionalInstanceInitialiserTest$Box;"));
        assertSame(enabled ? object.leftValue : object.rightValue, read(object, "choice", "Ljava/lang/Object;"));
        assertSame(enabled ? object.leftValue : null, read(object, "nullable", "Ljava/lang/Object;"));
        assertEquals(enabled ? 123456789012L : -7L, read(object, "wide", "J"));
        assertEquals(enabled ? 2.5 : -3.25, read(object, "decimal", "D"));
        assertEquals(enabled, read(object, "active", "Z"));
        assertEquals(enabled ? 7 : 9, read(object, "nested", "I"));
        assertEquals(enabled ? 11 : 12, ((Box) read(object, "boxed",
                "Lcom/onurkat/reclazz/reload/ConditionalInstanceInitialiserTest$Box;")).number());
        assertEquals(1, object.conditionCalls);
        assertEquals(enabled ? 1 : 0, object.leftCalls);
        assertEquals(enabled ? 0 : 1, object.rightCalls);
        object.enabled = !enabled;
        assertSame(enabled ? object.leftValue : object.rightValue, read(object, "choice", "Ljava/lang/Object;"));
        assertEquals(1, object.conditionCalls, "repeated reads retain the stored value");
        assertEquals(constructors, Fixture.constructors);
        assertEquals(99, object.existing);
    }

    @Test
    void twoExistingObjectsComputeIndependentlyAndKeepTheirValuesAfterRegistrationChanges() throws Throwable {
        Fixture one = new Fixture(true), two = new Fixture(false), unread = new Fixture(false);
        register(Set.of("choice:Ljava/lang/Object;", "nested:I"));
        assertSame(one.leftValue, read(one, "choice", "Ljava/lang/Object;"));
        assertSame(two.rightValue, read(two, "choice", "Ljava/lang/Object;"));
        one.secondary = false;
        assertEquals(8, read(one, "nested", "I"));
        one.enabled = false;
        two.enabled = true;
        unread.enabled = true;
        register(Set.of("choice:Ljava/lang/Object;"));
        assertSame(one.leftValue, read(one, "choice", "Ljava/lang/Object;"));
        assertSame(two.rightValue, read(two, "choice", "Ljava/lang/Object;"));
        assertSame(unread.leftValue, read(unread, "choice", "Ljava/lang/Object;"));
        assertEquals(1, one.conditionCalls);
        assertEquals(1, two.conditionCalls);
        assertEquals(1, unread.conditionCalls);
    }

    @Test
    void applicationWritesBeforeTheFirstReadWinIncludingNull() throws Throwable {
        Fixture one = new Fixture(true), two = new Fixture(false);
        register(Set.of("choice:Ljava/lang/Object;"));
        Object assigned = new Object();
        FieldStore.putExtField(one, assigned, owner(), "choice", "Ljava/lang/Object;");
        FieldStore.putExtField(two, null, owner(), "choice", "Ljava/lang/Object;");
        assertSame(assigned, read(one, "choice", "Ljava/lang/Object;"));
        assertNull(read(two, "choice", "Ljava/lang/Object;"));
        assertEquals(0, one.conditionCalls);
        assertEquals(0, two.conditionCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Parameter", "Guarded", "Outer", "Loop", "Multiple", "Constructors", "Shared",
            "StaticWrite", "Catching", "Switching", "ForeignReceiver", "Local", "ArrayWrite"})
    void unsafeConditionalInitialisersAreRefused(String name) throws Exception {
        Class<?> type = Class.forName(getClass().getName() + "$" + name, false, getClass().getClassLoader());
        var plan = InstanceInitialiserSlicer.planFor(bytes(type), Set.of("value:Ljava/lang/String;"));
        assertTrue(plan.isEmpty(), name + ": no partial initializer may be emitted");
        assertTrue(plan.refused.containsKey("value:Ljava/lang/String;"), name + ": " + plan.refused);
    }

    private static String owner() { return Fixture.class.getName().replace('.', '/'); }
    private static Object read(Fixture object, String name, String desc) {
        return FieldStore.getExtField(object, owner(), name, desc);
    }
    private static byte[] bytes(Class<?> type) throws Exception {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }
    private static void register(Set<String> keys) throws Throwable {
        byte[] bytes = bytes(Fixture.class);
        ClassNode source = new ClassNode();
        new ClassReader(bytes).accept(source, ClassReader.SKIP_CODE);
        List<TransformContext.MethodSig> methods = new ArrayList<>();
        List<TransformContext.FieldSig> fields = new ArrayList<>();
        for (var m : source.methods) methods.add(new TransformContext.MethodSig(m.name, m.desc, m.access));
        for (var f : source.fields) if (!keys.contains(f.name + ":" + f.desc))
            fields.add(new TransformContext.FieldSig(f.name, f.desc, f.access));
        var before = new TransformContext.ClassMetadata(methods, fields, 0, source.superName, Set.of());
        var diff = StructuralAnalyzer.analyze(before, bytes);
        var companion = CompanionGenerator.generate(source.name, bytes, diff, 1, Set.of());
        assertEquals(keys, companion.getInstancePlan().initialisers.keySet(), companion.getInstancePlan().refused.toString());
        var lookup = MethodHandles.privateLookupIn(Fixture.class, MethodHandles.lookup());
        var hidden = lookup.defineHiddenClass(companion.getBytecode(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        Map<String, MethodHandle> handles = new LinkedHashMap<>();
        for (String key : keys) {
            String name = key.substring(0, key.indexOf(':'));
            handles.put(key, hidden.findStatic(hidden.lookupClass(), InstanceInitialiserSlicer.methodName(name),
                    MethodType.methodType(Object.class, Fixture.class)).asType(MethodType.methodType(Object.class, Object.class)));
        }
        FieldStore.setInstanceInitialisers(Fixture.class, handles);
    }
}
