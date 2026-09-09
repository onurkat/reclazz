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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConditionalStaticInitialiserTest {
    record Box(int number) { }
    static class State {
        static boolean flag;
        static boolean other;
        static int conditions, leftCalls, rightCalls, blocks;
        static final Object LEFT = new Object(), RIGHT = new Object();
        static boolean condition() { conditions++; return flag; }
        static Object left() { leftCalls++; return LEFT; }
        static Object right() { rightCalls++; return RIGHT; }
        static Object explode() { throw new IllegalStateException("unselected branch ran"); }
    }
    static class Values {
        static int existing = 5;
        static Object CHOICE = State.condition() ? State.left() : State.right();
        static int NESTED = State.flag ? (State.other ? 7 : 8) : 9;
        static long WIDE = State.flag ? 123456789012L : -321L;
        static double DECIMAL = State.flag ? 2.5 : -3.25;
        static Object NULLABLE = State.flag ? State.LEFT : null;
        static Box BOX = new Box(State.flag ? 11 : 12);
        static boolean BOTH = State.flag && State.other;
        static int AFTER = CHOICE == State.LEFT ? 10 : 20;
        static { State.blocks++; }
    }
    static class Unselected {
        static Object VALUE = State.flag ? State.left() : State.explode();
    }
    static class PriorBlock {
        static { if (State.flag) State.blocks++; }
        static Object VALUE = State.flag ? State.LEFT : State.RIGHT;
    }
    static class Guarded {
        static int VALUE;
        static { if (State.flag) VALUE = 1; }
    }
    static class SharedWrite {
        static int existing;
        static int VALUE = State.flag ? (existing = 3) : 4;
    }
    static class Local {
        static int VALUE;
        static { int seed = State.flag ? 11 : 12; VALUE = State.other ? seed : 13; }
    }
    static class Loop {
        static int VALUE;
        static { while (State.flag) VALUE = State.other ? 1 : 2; }
    }
    static class DoLoop {
        static int VALUE;
        static { do { VALUE = State.other ? 1 : 2; } while (State.flag); }
    }
    static class OuterCondition {
        static int VALUE;
        static { if (State.flag) VALUE = State.other ? 1 : 2; }
    }
    static class Catching {
        static Object VALUE;
        static { try { VALUE = State.flag ? State.left() : State.explode(); } catch (RuntimeException e) { VALUE = null; } }
    }
    static class CatchSingle {
        static int VALUE;
        static { try { VALUE = State.flag ? 1 : 2; } catch (RuntimeException e) { State.blocks++; } }
    }
    static class ObjectWrite {
        static class Mutable { int number; }
        static Mutable existing = new Mutable();
        static int VALUE = State.flag ? (existing.number = 1) : 2;
    }
    static class Switching {
        static int VALUE = switch (State.conditions) { case 1 -> 2; case 7 -> 3; default -> 4; };
    }
    static class Multiple {
        static int VALUE = State.flag ? 1 : 2;
        static { VALUE = 3; }
    }
    static class ArrayWrite {
        static int[] existing = new int[1];
        static int VALUE = State.flag ? (existing[0] = 1) : 2;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aConditionalValueRunsOnlyItsSelectedBranch(boolean flag) throws Throwable {
        // Initialize the original class first, then distinguish all slice effects.
        assertNotNull(Values.CHOICE);
        Values.existing = 99;
        State.flag = flag;
        State.other = true;
        State.conditions = State.leftCalls = State.rightCalls = 0;
        int oldBlocks = State.blocks;
        Set<String> keys = new LinkedHashSet<>(List.of("CHOICE:Ljava/lang/Object;", "NESTED:I", "WIDE:J",
                "DECIMAL:D", "NULLABLE:Ljava/lang/Object;", "AFTER:I", "BOTH:Z",
                "BOX:Lcom/onurkat/reclazz/reload/ConditionalStaticInitialiserTest$Box;"));
        run(Values.class, keys);
        assertSame(flag ? State.LEFT : State.RIGHT, value(Values.class, "CHOICE", "Ljava/lang/Object;"));
        assertEquals(flag ? 7 : 9, value(Values.class, "NESTED", "I"));
        assertEquals(flag ? 123456789012L : -321L, value(Values.class, "WIDE", "J"));
        assertEquals(flag ? 2.5 : -3.25, value(Values.class, "DECIMAL", "D"));
        assertSame(flag ? State.LEFT : null, value(Values.class, "NULLABLE", "Ljava/lang/Object;"));
        assertEquals(flag, value(Values.class, "BOTH", "Z"));
        assertEquals(flag ? 11 : 12, ((Box) value(Values.class, "BOX",
                "Lcom/onurkat/reclazz/reload/ConditionalStaticInitialiserTest$Box;")).number());
        assertEquals(flag ? 10 : 20, value(Values.class, "AFTER", "I"), "added fields initialize in source order");
        assertEquals(1, State.conditions);
        assertEquals(flag ? 1 : 0, State.leftCalls);
        assertEquals(flag ? 0 : 1, State.rightCalls);
        assertEquals(oldBlocks, State.blocks, "the old static block must not run again");
        assertEquals(99, Values.existing, "existing application state must not reset");
    }

    @Test
    void nestedFalseArmAndThrowingUnselectedArm() throws Throwable {
        assertNotNull(Values.CHOICE);
        State.flag = true;
        State.other = false;
        run(Values.class, Set.of("NESTED:I"));
        assertEquals(8, value(Values.class, "NESTED", "I"));
        assertSame(State.LEFT, Unselected.VALUE);
        State.leftCalls = 0;
        run(Unselected.class, Set.of("VALUE:Ljava/lang/Object;"));
        assertSame(State.LEFT, value(Unselected.class, "VALUE", "Ljava/lang/Object;"));
        assertEquals(1, State.leftCalls);
    }

    @Test
    void aPreviousConditionalStaticBlockIsNotAbsorbed() throws Throwable {
        State.flag = true;
        assertNotNull(PriorBlock.VALUE);
        int blocks = State.blocks;
        run(PriorBlock.class, Set.of("VALUE:Ljava/lang/Object;"));
        assertSame(State.LEFT, value(PriorBlock.class, "VALUE", "Ljava/lang/Object;"));
        assertEquals(blocks, State.blocks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Guarded", "SharedWrite", "Local", "Loop", "DoLoop", "OuterCondition",
            "Catching", "CatchSingle", "Switching", "Multiple", "ArrayWrite", "ObjectWrite"})
    void intertwinedInitialisersStayRefused(String fixture) throws Exception {
        Class<?> owner = Class.forName(getClass().getName() + "$" + fixture, false, getClass().getClassLoader());
        String key = fixture.equals("Catching") ? "VALUE:Ljava/lang/Object;" : "VALUE:I";
        var plan = StaticInitialiserSlicer.planFor(bytes(owner), Set.of(key));
        assertFalse(plan.hasCode(), fixture + ": must not emit partial code");
        assertTrue(plan.slicedKeys.isEmpty());
        assertTrue(plan.refused.containsKey(key), fixture + ": " + plan.refused);
    }

    private static Object value(Class<?> owner, String name, String desc) {
        return FieldStore.getStaticExtField(owner, name, desc);
    }
    private static byte[] bytes(Class<?> owner) throws Exception {
        try (var in = owner.getResourceAsStream("/" + owner.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }
    private static void run(Class<?> owner, Set<String> keys) throws Throwable {
        byte[] bytes = bytes(owner);
        ClassNode source = new ClassNode();
        new ClassReader(bytes).accept(source, ClassReader.SKIP_CODE);
        List<TransformContext.MethodSig> methods = new ArrayList<>();
        List<TransformContext.FieldSig> fields = new ArrayList<>();
        for (var m : source.methods) methods.add(new TransformContext.MethodSig(m.name, m.desc, m.access));
        for (var f : source.fields) if (!keys.contains(f.name + ":" + f.desc))
            fields.add(new TransformContext.FieldSig(f.name, f.desc, f.access));
        var before = new TransformContext.ClassMetadata(methods, fields, 0, source.superName, Set.of());
        var diff = StructuralAnalyzer.analyze(before, bytes);
        assertEquals(keys, diff.getAddedFields());
        var companion = CompanionGenerator.generate(source.name, bytes, diff, 1, keys);
        assertEquals(keys, companion.getStaticPlan().slicedKeys, companion.getStaticPlan().refused.toString());
        assertTrue(companion.getStaticPlan().hasCode());
        var lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
        var hidden = lookup.defineHiddenClass(companion.getBytecode(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        hidden.findStatic(hidden.lookupClass(), StaticInitialiserSlicer.INIT_METHOD, MethodType.methodType(void.class)).invokeExact();
    }
}
