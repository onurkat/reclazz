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

class RicherInitialiserTest implements Opcodes {
    static class State {
        static int selector, choices, left, right, defaults, blocks;
        static int pick() { choices++; return selector; }
        static String left() { left++; return "left"; }
        static String right() { right++; return "right"; }
        static String other() { defaults++; return "default"; }
        static void work() { blocks++; }
    }
    static class InstanceSwitch {
        private Object[] __reclazz$ext;
        static int constructors, delegations;
        int selector, choices, left, right, defaults;
        String dense = switch (pick()) { case 0 -> left(); case 1 -> right(); case 2 -> null; default -> other(); };
        long sparse = switch (selector) { case 7 -> 77L; case 1000 -> 99L; default -> -1L; };
        InstanceSwitch() { this(0); delegations++; }
        InstanceSwitch(int selector) { this.selector = selector; constructors++; choices = left = right = defaults = 0; }
        InstanceSwitch(long selector) { this.selector = (int) selector; constructors++; choices = left = right = defaults = 0; }
        int pick() { choices++; return selector; }
        String left() { left++; return "left"; }
        String right() { right++; return "right"; }
        String other() { defaults++; return "default"; }
    }
    static class StaticSwitch {
        static String dense = switch (State.pick()) { case 0 -> State.left(); case 1 -> State.right(); case 2 -> null; default -> State.other(); };
        static long sparse = switch (State.selector) { case 7 -> 77L; case 1000 -> 99L; default -> -1L; };
    }
    static class InstanceHandler {
        private Object[] __reclazz$ext;
        int selector, blocks;
        { try { blocks++; } catch (RuntimeException e) { blocks++; } }
        String value = selector == 0 ? "zero" : "other";
        { try { blocks++; } catch (RuntimeException e) { blocks++; } }
        InstanceHandler() { }
        InstanceHandler(int selector) { this(); this.selector = selector; try { blocks++; } catch (RuntimeException e) { blocks++; } }
    }
    static class StaticHandler {
        static { try { State.work(); } catch (RuntimeException e) { State.work(); } }
        static String value = State.selector == 0 ? "zero" : "other";
        static { try { State.work(); } catch (RuntimeException e) { State.work(); } }
    }
    static class OverlapI { int selector; String value; OverlapI() { try { value = selector == 0 ? "a" : "b"; } catch (RuntimeException e) { selector++; } } }
    static class OverlapS { static String value; static { try { value = State.selector == 0 ? "a" : "b"; } catch (RuntimeException e) { State.work(); } } }
    static class EarlyExitI { int selector; String value; EarlyExitI() { try { State.work(); } catch (RuntimeException e) { return; } value = selector == 0 ? "a" : "b"; } }
    static class LocalI { int selector; String value; LocalI() { String x = "x"; value = switch(selector) { case 1 -> x; default -> "b"; }; } }
    static class LocalS { static String value; static { String x = "x"; value = switch(State.selector) { case 1 -> x; default -> "b"; }; } }
    static class SharedI { int selector; String other; String value = switch(selector) { case 1 -> other = "a"; default -> "b"; }; }
    static class SharedS { static String other; static String value = switch(State.selector) { case 1 -> other = "a"; default -> "b"; }; }
    static class OuterI { int selector; String value; OuterI() { if (selector > 0) value = switch(selector) { case 1 -> "a"; default -> "b"; }; } }
    static class OuterS { static String value; static { if (State.selector > 0) value = switch(State.selector) { case 1 -> "a"; default -> "b"; }; } }
    static class LoopI { int selector; String value; LoopI() { do { value = switch(selector) { case 1 -> "a"; default -> "b"; }; } while (selector > 0); } }
    static class LoopS { static String value; static { do { value = switch(State.selector) { case 1 -> "a"; default -> "b"; }; } while (State.selector > 0); } }

    static class LoopHandlerI { int selector; String value; LoopHandlerI() { try { State.work(); } catch (RuntimeException e) { while (selector > 0) State.work(); } value = selector == 0 ? "a" : "b"; } }
    static class LoopHandlerS { static String value; static { try { State.work(); } catch (RuntimeException e) { while (State.selector > 0) State.work(); } value = State.selector == 0 ? "a" : "b"; } }

    @Test void instanceSwitchExpressions() throws Exception {
        var plan = InstanceInitialiserSlicer.planFor(bytes(InstanceSwitch.class), switchKeys());
        assertEquals(switchKeys(), plan.initialisers.keySet(), plan.refused.toString());
    }
    @Test void staticSwitchExpressions() throws Exception {
        var plan = StaticInitialiserSlicer.planFor(bytes(StaticSwitch.class), switchKeys());
        assertEquals(switchKeys(), plan.slicedKeys, plan.refused.toString());
    }
    @Test void instanceUnrelatedHandlers() throws Exception {
        var plan = InstanceInitialiserSlicer.planFor(bytes(InstanceHandler.class), valueKey());
        assertEquals(valueKey(), plan.initialisers.keySet(), plan.refused.toString());
    }
    @Test void staticUnrelatedHandlers() throws Exception {
        var plan = StaticInitialiserSlicer.planFor(bytes(StaticHandler.class), valueKey());
        assertEquals(valueKey(), plan.slicedKeys, plan.refused.toString());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 7, 1000})
    void isolatedTableAndLookupSwitchInitialiseAddedFields(int selector) throws Throwable {
        InstanceSwitch[] objects = {new InstanceSwitch(selector), new InstanceSwitch((long) selector), new InstanceSwitch()};
        objects[2].selector = selector;
        int constructors = InstanceSwitch.constructors, delegations = InstanceSwitch.delegations;
        var source = node(bytes(InstanceSwitch.class));
        assertTrue(source.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).anyMatch(i -> i instanceof TableSwitchInsnNode));
        assertTrue(source.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).anyMatch(i -> i instanceof LookupSwitchInsnNode));
        install(InstanceSwitch.class, switchKeys(), false);
        String expected = selector == 0 ? "left" : selector == 1 ? "right" : selector == 2 ? null : "default";
        long wide = selector == 7 ? 77L : selector == 1000 ? 99L : -1L;
        for (InstanceSwitch object : objects) {
            assertEquals(expected, read(object, "dense", "Ljava/lang/String;"));
            assertEquals(wide, read(object, "sparse", "J"));
            assertEquals(1, object.choices);
            assertEquals(selector == 0 ? 1 : 0, object.left);
            assertEquals(selector == 1 ? 1 : 0, object.right);
            assertEquals(selector > 2 ? 1 : 0, object.defaults);
            object.selector = -99;
            assertEquals(expected, read(object, "dense", "Ljava/lang/String;"));
            assertEquals(1, object.choices);
        }
        assertEquals(constructors, InstanceSwitch.constructors); assertEquals(delegations, InstanceSwitch.delegations);
        Class.forName(StaticSwitch.class.getName(), true, getClass().getClassLoader());
        State.selector = selector; State.choices = State.left = State.right = State.defaults = 0;
        source = node(bytes(StaticSwitch.class));
        assertTrue(source.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).anyMatch(i -> i instanceof TableSwitchInsnNode));
        assertTrue(source.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).anyMatch(i -> i instanceof LookupSwitchInsnNode));
        install(StaticSwitch.class, switchKeys(), true);
        assertEquals(expected, FieldStore.getStaticExtField(StaticSwitch.class, "dense", "Ljava/lang/String;"));
        assertEquals(wide, FieldStore.getStaticExtField(StaticSwitch.class, "sparse", "J"));
        assertEquals(1, State.choices);
        assertEquals(selector == 0 ? 1 : 0, State.left); assertEquals(selector == 1 ? 1 : 0, State.right);
        assertEquals(selector > 2 ? 1 : 0, State.defaults);
    }

    @Test void unrelatedHandlerDoesNotRejectIsolatedInitialiser() throws Throwable {
        InstanceHandler object = new InstanceHandler(7);
        int blocks = object.blocks;
        install(InstanceHandler.class, valueKey(), false);
        assertEquals("other", read(object, "value", "Ljava/lang/String;"));
        assertEquals(blocks, object.blocks);
        FieldStore.putExtField(object, null, InstanceHandler.class.getName().replace('.', '/'), "value", "Ljava/lang/String;");
        install(InstanceHandler.class, valueKey(), false);
        assertNull(read(object, "value", "Ljava/lang/String;"));
        Class.forName(StaticHandler.class.getName(), true, getClass().getClassLoader());
        blocks = State.blocks; State.selector = 7;
        install(StaticHandler.class, valueKey(), true);
        assertEquals("other", FieldStore.getStaticExtField(StaticHandler.class, "value", "Ljava/lang/String;"));
        assertEquals(blocks, State.blocks);
    }

    @ParameterizedTest @ValueSource(strings = {"OverlapI", "OverlapS", "EarlyExitI", "LoopHandlerI", "LoopHandlerS", "LocalI", "LocalS", "SharedI", "SharedS", "OuterI", "OuterS", "LoopI", "LoopS"})
    void overlappingHandlerOrExternalControlFlowRemainsRefused(String fixture) throws Exception {
        Class<?> type = Class.forName(getClass().getName() + "$" + fixture, false, getClass().getClassLoader());
        if (fixture.endsWith("I")) {
            var plan = InstanceInitialiserSlicer.planFor(bytes(type), valueKey());
            assertTrue(plan.isEmpty(), fixture); assertEquals(valueKey(), plan.refused.keySet(), fixture);
        } else {
            var plan = StaticInitialiserSlicer.planFor(bytes(type), valueKey());
            assertFalse(plan.hasCode(), fixture); assertEquals(valueKey(), plan.refused.keySet(), fixture);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"backward-switch", "escaping-switch", "handler-skip", "handler-repeat", "handler-entry"})
    void exceptionalAndSwitchEdgesCannotEscapeTheSlice(String mode) throws Exception {
        for (boolean instance : List.of(false, true)) {
            ClassNode cls = node(bytes(instance ? InstanceSwitch.class : StaticSwitch.class));
            MethodNode method = cls.methods.stream().filter(m -> instance ? m.name.equals("<init>") && m.desc.equals("(I)V") : m.name.equals("<clinit>")).findFirst().orElseThrow();
            TableSwitchInsnNode table = (TableSwitchInsnNode) Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof TableSwitchInsnNode).findFirst().orElseThrow();
            AbstractInsnNode begin = instance ? Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode m && m.name.equals("<init>")).findFirst().orElseThrow().getNext() : method.instructions.getFirst();
            if (mode.equals("backward-switch")) {
                LabelNode target = new LabelNode();
                method.instructions.insertBefore(instance ? table.getPrevious().getPrevious() : table.getPrevious(), target);
                table.dflt = target;
            } else if (mode.equals("escaping-switch")) {
                LabelNode target = new LabelNode();
                method.instructions.insertBefore(method.instructions.getLast(), target);
                table.dflt = target;
            } else {
                LabelNode from = new LabelNode(), to = new LabelNode(), handler = new LabelNode();
                InsnList protectedCall = new InsnList();
                protectedCall.add(from);
                protectedCall.add(new MethodInsnNode(INVOKESTATIC, State.class.getName().replace('.', '/'), "work", "()V", false));
                protectedCall.add(to);
                if (mode.equals("handler-skip")) {
                    method.instructions.insertBefore(begin, protectedCall);
                    method.instructions.add(handler); method.instructions.add(new InsnNode(POP)); method.instructions.add(new InsnNode(RETURN));
                } else {
                    AbstractInsnNode exit = Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() == RETURN).findFirst().orElseThrow();
                    method.instructions.insertBefore(exit, protectedCall);
                    if (mode.equals("handler-entry")) handler = table.labels.get(0);
                    else {
                        LabelNode entry = new LabelNode(); InsnList prefix = new InsnList();
                        prefix.add(new JumpInsnNode(GOTO, entry)); prefix.add(handler); prefix.add(new InsnNode(POP)); prefix.add(entry);
                        method.instructions.insertBefore(begin, prefix);
                    }
                }
                method.tryCatchBlocks.add(new TryCatchBlockNode(from, to, handler, "java/lang/RuntimeException"));
            }
            ClassWriter writer = new ClassWriter(0); cls.accept(writer);
            if (instance) {
                var plan = InstanceInitialiserSlicer.planFor(writer.toByteArray(), Set.of("dense:Ljava/lang/String;"));
                assertTrue(plan.isEmpty(), mode); assertTrue(plan.refused.containsKey("dense:Ljava/lang/String;"), mode);
            } else {
                var plan = StaticInitialiserSlicer.planFor(writer.toByteArray(), Set.of("dense:Ljava/lang/String;"));
                assertFalse(plan.hasCode(), mode); assertTrue(plan.refused.containsKey("dense:Ljava/lang/String;"), mode);
            }
        }
    }

    private static Set<String> switchKeys() { return Set.of("dense:Ljava/lang/String;", "sparse:J"); }
    private static Set<String> valueKey() { return Set.of("value:Ljava/lang/String;"); }
    private static Object read(Object object, String name, String desc) { return FieldStore.getExtField(object, object.getClass().getName().replace('.', '/'), name, desc); }
    private static byte[] bytes(Class<?> type) throws Exception {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { assertNotNull(in); return in.readAllBytes(); }
    }
    private static ClassNode node(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
    private static void install(Class<?> owner, Set<String> keys, boolean statics) throws Throwable {
        byte[] bytes = bytes(owner); ClassNode source = node(bytes);
        List<TransformContext.MethodSig> methods = new ArrayList<>(); List<TransformContext.FieldSig> fields = new ArrayList<>();
        for (var m : source.methods) methods.add(new TransformContext.MethodSig(m.name, m.desc, m.access));
        for (var f : source.fields) if (!keys.contains(f.name + ":" + f.desc)) fields.add(new TransformContext.FieldSig(f.name, f.desc, f.access));
        var before = new TransformContext.ClassMetadata(methods, fields, 0, source.superName, Set.of());
        var companion = CompanionGenerator.generate(source.name, bytes, StructuralAnalyzer.analyze(before, bytes), 1, statics ? keys : Set.of());
        if (statics) assertEquals(keys, companion.getStaticPlan().slicedKeys, companion.getStaticPlan().refused.toString());
        else assertEquals(keys, companion.getInstancePlan().initialisers.keySet(), companion.getInstancePlan().refused.toString());
        var lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
        var hidden = lookup.defineHiddenClass(companion.getBytecode(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        if (statics) hidden.findStatic(hidden.lookupClass(), StaticInitialiserSlicer.INIT_METHOD, MethodType.methodType(void.class)).invokeExact();
        else {
            Map<String, MethodHandle> handles = new LinkedHashMap<>();
            for (String key : keys) handles.put(key, hidden.findStatic(hidden.lookupClass(), InstanceInitialiserSlicer.methodName(key.substring(0, key.indexOf(':'))),
                    MethodType.methodType(Object.class, owner)).asType(MethodType.methodType(Object.class, Object.class)));
            FieldStore.setInstanceInitialisers(owner, handles);
        }
    }
}
