/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.transform.TransformContext;
import java.lang.invoke.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiConstructorInitialiserTest implements Opcodes {
    static class Routes {
        private Object[] __reclazz$ext;
        static int constructors, delegations;
        boolean flag;
        int calls, existing = 17;
        String value = choose() ? "yes" : "no";
        Object nullable = flag ? null : "present";
        int number = flag ? 7 : 9;
        long wide = flag ? 123456789012L : -7L;
        double decimal = flag ? 2.5 : -3.25;
        Routes() { this(1L, 2); delegations++; }
        Routes(boolean flag) { this.flag = flag; constructors++; calls = 0; }
        Routes(long ignored, int alsoIgnored) { flag = false; constructors++; calls = 0; }
        boolean choose() { calls++; return flag; }
    }
    static class Different {
        boolean flag; String value;
        Different() { value = flag ? "a" : "b"; }
        Different(int ignored) { value = flag ? "a" : "c"; }
    }
    static class Missing {
        boolean flag; String value;
        Missing() { value = flag ? "a" : "b"; }
        Missing(int ignored) { }
    }
    static class Parameter {
        boolean flag; String value;
        Parameter() { value = flag ? "a" : "b"; }
        Parameter(boolean input) { value = input ? "a" : "b"; }
    }
    static class Overwritten {
        boolean flag; String value = flag ? "a" : "b";
        Overwritten() { }
        Overwritten(int ignored) { this(); value = "later"; }
    }
    static class DifferentShape {
        boolean flag; String value;
        DifferentShape() { value = "a"; }
        DifferentShape(int ignored) { value = flag ? "a" : "b"; }
    }
    static class MixedWrites {
        boolean flag; String value;
        MixedWrites() { value = "a"; value = flag ? "b" : "c"; }
        MixedWrites(int ignored) { value = flag ? "b" : "c"; }
    }
    static class UnrelatedHandler {
        boolean flag; String value = flag ? "a" : "b";
        UnrelatedHandler() { }
        UnrelatedHandler(int ignored) { this(); try { System.getProperty("x"); } catch (RuntimeException ex) { } }
    }
    static class Parent {
        Parent() { }
        Parent(Parent argument) { }
    }
    static class SuperArgument extends Parent {
        boolean flag;
        String value = flag ? "a" : "b";
        SuperArgument() { super(new Parent()); }
        SuperArgument(boolean ignored) { super(); }
    }

    @Test void objectConstructionInSuperArgumentsIsNotADelegationRoute() throws Exception {
        var plan = InstanceInitialiserSlicer.planFor(bytes(SuperArgument.class), Set.of("value:Ljava/lang/String;"));
        assertEquals(Set.of("value:Ljava/lang/String;"), plan.initialisers.keySet(), plan.refused.toString());
    }

    @Test void equivalentConstructorRoutesInitialiseOldObjects() throws Throwable {
        Routes[] objects = {new Routes(true), new Routes(8L, 2), new Routes()};
        int constructors = Routes.constructors, delegations = Routes.delegations;
        register();
        for (Routes object : objects) {
            object.existing = 99;
            assertEquals(object.flag ? "yes" : "no", read(object, "value", "Ljava/lang/String;"));
            assertEquals(object.flag ? null : "present", read(object, "nullable", "Ljava/lang/Object;"));
            assertEquals(object.flag ? 7 : 9, read(object, "number", "I"));
            assertEquals(object.flag ? 123456789012L : -7L, read(object, "wide", "J"));
            assertEquals(object.flag ? 2.5 : -3.25, read(object, "decimal", "D"));
            assertEquals(1, object.calls); assertEquals(99, object.existing);
        }
        assertEquals(constructors, Routes.constructors); assertEquals(delegations, Routes.delegations);
    }

    @Test void delegatesDoNotReplayAndWritesIncludingNullSurvive() throws Throwable {
        Routes value = new Routes(), nulled = new Routes(true), unread = new Routes(1L, 3);
        int constructors = Routes.constructors, delegations = Routes.delegations;
        register();
        FieldStore.putExtField(value, "user", owner(), "value", "Ljava/lang/String;");
        FieldStore.putExtField(nulled, null, owner(), "value", "Ljava/lang/String;");
        unread.flag = true; register();
        assertEquals("user", read(value, "value", "Ljava/lang/String;"));
        assertNull(read(nulled, "value", "Ljava/lang/String;"));
        assertEquals("yes", read(unread, "value", "Ljava/lang/String;"));
        unread.flag = false; register();
        assertEquals("yes", read(unread, "value", "Ljava/lang/String;"));
        assertEquals(0, value.calls); assertEquals(0, nulled.calls); assertEquals(1, unread.calls);
        assertEquals(constructors, Routes.constructors); assertEquals(delegations, Routes.delegations);
    }

    @ParameterizedTest @ValueSource(strings={"Different","Missing","Parameter","Overwritten","DifferentShape","MixedWrites"})
    void differentMissingOrParameterDependentInitialisersRemainRefused(String name) throws Exception {
        Class<?> type = Class.forName(getClass().getName()+"$"+name, false, getClass().getClassLoader());
        ClassNode node = node(bytes(type));
        for (int order=0;order<2;order++) {
            var plan = InstanceInitialiserSlicer.planFor(write(node), Set.of("value:Ljava/lang/String;"));
            assertTrue(plan.isEmpty(), name + plan.initialisers.keySet());
            assertTrue(plan.refused.containsKey("value:Ljava/lang/String;"), name + plan.refused);
            Collections.reverse(node.methods);
        }
    }

    @Test void unrelatedHandlerAfterDelegationIsAccepted() throws Exception {
        var plan = InstanceInitialiserSlicer.planFor(bytes(UnrelatedHandler.class), Set.of("value:Ljava/lang/String;"));
        assertEquals(Set.of("value:Ljava/lang/String;"), plan.initialisers.keySet(), plan.refused.toString());
    }

    @Test void constructorOrderAndDebugLayoutDoNotChooseAValue() throws Exception {
        ClassNode node = node(bytes(Routes.class));
        Collections.reverse(node.methods);
        for (MethodNode method : node.methods) if (method.name.equals("<init>")) {
            LabelNode label = new LabelNode();
            method.instructions.insert(label);
            method.instructions.insert(label, new LineNumberNode(4321, label));
            // Different constructor-local layouts must not participate in value equality.
            method.maxLocals += 5;
        }
        var plan = InstanceInitialiserSlicer.planFor(write(node), keys());
        assertEquals(keys(), plan.initialisers.keySet(), plan.refused.toString());
    }

    @Test void unknownOrCyclicDelegationRemainsRefused() throws Exception {
        for (String mode : List.of("unknown", "cycle", "overwritten-this", "before-super")) {
            ClassNode node = node(bytes(Routes.class));
            MethodNode ctor = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals("()V")).findFirst().orElseThrow();
            if (mode.equals("overwritten-this")) {
                InsnList overwrite = new InsnList(); overwrite.add(new VarInsnNode(ALOAD,0)); overwrite.add(new VarInsnNode(ASTORE,0));
                ctor.instructions.insert(overwrite);
            } else if (mode.equals("before-super")) {
                LabelNode target = new LabelNode(); ctor.instructions.insert(target); ctor.instructions.insert(new JumpInsnNode(GOTO,target));
            } else for (AbstractInsnNode insn : ctor.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("<init>") && call.owner.equals(node.name)) {
                    if (mode.equals("unknown")) call.desc = "(DI)V"; // same stack widths, absent target
                    else {
                        MethodNode delegated = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(call.desc)).findFirst().orElseThrow();
                        // Both routes only delegate, so rejection proves cycle detection, not a second write.
                        delegated.instructions.clear();
                        delegated.instructions.add(new VarInsnNode(ALOAD, 0));
                        delegated.instructions.add(new MethodInsnNode(INVOKESPECIAL, node.name, "<init>", "()V", false));
                        delegated.instructions.add(new InsnNode(RETURN));
                        delegated.localVariables = null;
                    }
                    break;
                }
            }
            var plan = InstanceInitialiserSlicer.planFor(write(node), keys());
            assertTrue(plan.isEmpty(), mode); assertEquals(keys(), plan.refused.keySet(), mode);
            if (mode.equals("cycle"))
                assertTrue(plan.refused.values().stream().allMatch(reason -> reason.contains("cycle")), plan.refused.toString());
        }
    }

    private static Set<String> keys() { return Set.of("value:Ljava/lang/String;", "nullable:Ljava/lang/Object;", "number:I", "wide:J", "decimal:D"); }
    private static String owner() { return Routes.class.getName().replace('.', '/'); }
    private static Object read(Routes object, String name, String desc) { return FieldStore.getExtField(object, owner(), name, desc); }
    private static byte[] bytes(Class<?> type) throws Exception {
        try (var in = type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) { assertNotNull(in); return in.readAllBytes(); }
    }
    private static ClassNode node(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n,0); return n; }
    private static byte[] write(ClassNode n) { ClassWriter w = new ClassWriter(0); n.accept(w); return w.toByteArray(); }
    private static void register() throws Throwable {
        byte[] bytes = bytes(Routes.class); ClassNode source = node(bytes);
        List<TransformContext.MethodSig> methods = new ArrayList<>(); List<TransformContext.FieldSig> fields = new ArrayList<>();
        for (var m : source.methods) methods.add(new TransformContext.MethodSig(m.name,m.desc,m.access));
        for (var f : source.fields) if (!keys().contains(f.name+":"+f.desc)) fields.add(new TransformContext.FieldSig(f.name,f.desc,f.access));
        var before = new TransformContext.ClassMetadata(methods,fields,0,source.superName,Set.of());
        var companion = CompanionGenerator.generate(source.name,bytes,StructuralAnalyzer.analyze(before,bytes),1,Set.of());
        assertEquals(keys(),companion.getInstancePlan().initialisers.keySet(),companion.getInstancePlan().refused.toString());
        var lookup = MethodHandles.privateLookupIn(Routes.class,MethodHandles.lookup());
        var hidden = lookup.defineHiddenClass(companion.getBytecode(),true,MethodHandles.Lookup.ClassOption.NESTMATE);
        Map<String,MethodHandle> handles = new LinkedHashMap<>();
        for (String key : keys()) handles.put(key,hidden.findStatic(hidden.lookupClass(),InstanceInitialiserSlicer.methodName(key.substring(0,key.indexOf(':'))),
                MethodType.methodType(Object.class,Routes.class)).asType(MethodType.methodType(Object.class,Object.class)));
        FieldStore.setInstanceInitialisers(Routes.class,handles);
    }
}
