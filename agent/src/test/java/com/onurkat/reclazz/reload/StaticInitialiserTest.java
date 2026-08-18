/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.transform.TransformContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A static field a reload adds, with the value its initialiser would have
 * given it.
 *
 * <p>The field itself has worked for a while. The value did not: the
 * initialiser is in {@code <clinit>}, the JVM runs that once and never again,
 * and re-running all of it would reset every other static the application has
 * been mutating since startup. So the field read as null or zero, and the only
 * honest thing to do was say so.
 *
 * <p>What these tests hold in place is the narrower move that is actually
 * safe: take the part of {@code <clinit>} that belongs to the new field, leave
 * the rest, and where the two cannot be separated, keep saying so. The refusal
 * cases matter more than the success cases here, because a wrong acceptance
 * runs application code nobody asked to re-run.
 */
class StaticInitialiserTest {

    // ── What javac hands us ───────────────────────────────────────────────

    /**
     * A compile-time constant never reaches {@code <clinit>}: javac writes it
     * into a ConstantValue attribute on the field. So the most common added
     * static of all, a constant, needs no code run at all.
     */
    @Test
    void constantsComeFromTheFieldItself() throws IOException {
        var plan = planFor(Fixture.class, "MAX:I", "NAME:Ljava/lang/String;");

        assertEquals(50, plan.constants.get("MAX:I"));
        assertEquals("reclazz", plan.constants.get("NAME:Ljava/lang/String;"));
        assertTrue(plan.refused.isEmpty(), "nothing here needs refusing: " + plan.refused);
    }

    /**
     * The constant pool has no boolean, byte, short or char, so all four
     * arrive as Integer. Storing the Integer would leave a getstatic unboxing
     * a Character out of an Integer, which is a ClassCastException in code
     * that has nothing to do with the reload.
     */
    @Test
    void narrowConstantsComeBackAsTheirOwnType() {
        assertEquals(true, StaticInitialiserSlicer.narrowConstant("Z", 1));
        assertEquals(false, StaticInitialiserSlicer.narrowConstant("Z", 0));
        assertEquals(';', StaticInitialiserSlicer.narrowConstant("C", (int) ';'));
        assertEquals((byte) 7, StaticInitialiserSlicer.narrowConstant("B", 7));
        assertEquals((short) 7, StaticInitialiserSlicer.narrowConstant("S", 7));
        assertEquals(7, StaticInitialiserSlicer.narrowConstant("I", 7),
                "an int is already an int and must not be narrowed to anything");
    }

    /**
     * Everything else is a run of instructions in {@code <clinit>} that starts
     * with an empty operand stack and ends at this field's PUTSTATIC. That
     * shape is what makes the whole thing tractable.
     */
    @Test
    void aRealInitialiserIsSlicedOutOfClinit() throws IOException {
        var plan = planFor(Fixture.class, "CACHE:Ljava/util/Map;", "counter:I");

        assertEquals(Set.of("CACHE:Ljava/util/Map;", "counter:I"), plan.slicedKeys);
        assertTrue(plan.hasCode(), "there is code to run");
        assertTrue(plan.refused.isEmpty(), "nothing to refuse here: " + plan.refused);
    }

    // ── What it refuses, and why that is the point ────────────────────────

    /**
     * Two fields from one computation cannot be separated: taking the second
     * one's write means re-running the call that produced both, and writing
     * over a field the application has owned since startup.
     */
    @Test
    void aSharedComputationIsRefused() throws IOException {
        var plan = planFor(Tangled.class, "B:I");

        assertTrue(plan.slicedKeys.isEmpty(), "nothing may be sliced out of this");
        assertEquals(1, plan.refused.size());
        assertTrue(plan.refused.get("B:I").contains("computed earlier"),
                "the reason has to name the actual problem: " + plan.refused);
    }

    /**
     * A conditional initialiser is not straight-line code, so there is no
     * single run of instructions to lift out. Which of the control-flow checks
     * catches it depends on where javac puts the merge point, and both answers
     * are the same answer: leave it alone.
     */
    @Test
    void aBranchingInitialiserIsRefused() throws IOException {
        var plan = planFor(Branchy.class, "MODE:Ljava/lang/String;");

        assertTrue(plan.slicedKeys.isEmpty());
        String reason = plan.refused.get("MODE:Ljava/lang/String;");
        assertNotNull(reason, "it has to be refused, not silently skipped");
        assertTrue(reason.contains("branch") || reason.contains("jump"),
                "the reason has to be about control flow: " + reason);
    }

    @Test
    void aFieldWithNoInitialiserSaysSoRatherThanNothing() throws IOException {
        var plan = planFor(Fixture.class, "spare:J");

        assertTrue(plan.refused.containsKey("spare:J"));
        assertFalse(plan.hasCode());
    }

    /**
     * A field the reload did not add is not this code's business, and asking
     * for none of them must not produce a method to run.
     */
    @Test
    void nothingAddedMeansNothingToRun() throws IOException {
        var plan = StaticInitialiserSlicer.planFor(bytecodeOf(Fixture.class), Set.of());

        assertFalse(plan.hasCode());
        assertTrue(plan.initialisedKeys().isEmpty());
    }

    // ── Running it for real ───────────────────────────────────────────────

    /**
     * The slice is not worth much as a plan; what matters is the value in the
     * store afterwards. So this defines the companion the way a reload does,
     * as a nestmate of the target, and calls the method it generated.
     */
    @Test
    void theSlicedInitialiserRunsAndTheValueLands() throws Throwable {
        Set<String> added = Set.of("CACHE:Ljava/util/Map;", "counter:I");
        runInitialiser(Fixture.class, added);

        Object cache = FieldStore.getStaticExtField(
                internalNameOf(Fixture.class), "CACHE", "Ljava/util/Map;");
        assertInstanceOf(ConcurrentHashMap.class, cache,
                "the field must hold what its initialiser builds, not null");
        assertEquals(7, FieldStore.getStaticExtField(
                internalNameOf(Fixture.class), "counter", "I"),
                "and a computed int must be the computed value, not zero");
    }

    /**
     * The reason for slicing rather than replaying {@code <clinit>}: a static
     * block with a side effect must not run a second time. Here the block
     * counts its own runs, and the count has to stay where class loading left
     * it.
     */
    @Test
    void aSideEffectingStaticBlockIsLeftAlone() throws Throwable {
        int before = SideEffect.timesRun;
        assertEquals(1, before, "the JVM ran the static block once, at class load");

        runInitialiser(SideEffect.class, Set.of("LOOKUP:Ljava/util/Map;"));

        assertEquals(1, SideEffect.timesRun,
                "slicing exists so that re-running the block never happens; "
                + "a replay of <clinit> would make this 2");
        assertInstanceOf(HashMap.class, FieldStore.getStaticExtField(
                internalNameOf(SideEffect.class), "LOOKUP", "Ljava/util/Map;"),
                "and the field it was asked about still gets its value");
    }

    // ── The trap that only shows on the second reload ─────────────────────

    /**
     * A field a reload adds never enters the loaded class's schema, because
     * the JVM cannot add one. So the next reload of that class diffs it as
     * added all over again. An initialiser that ran every time would empty a
     * cache because somebody edited a method body two files away, and it would
     * look like a bug in the application.
     */
    @Test
    void aFieldIsInitialisedOnceAndThenLeftWhereTheApplicationPutIt() {
        String owner = "demo/Repeated";

        assertTrue(FieldStore.initialiseStaticOnce(owner, "CACHE", "Ljava/util/Map;", new HashMap<>()));

        @SuppressWarnings("unchecked")
        Map<String, String> live = (Map<String, String>)
                FieldStore.getStaticExtField(owner, "CACHE", "Ljava/util/Map;");
        live.put("warm", "yes");

        assertFalse(FieldStore.initialiseStaticOnce(owner, "CACHE", "Ljava/util/Map;", new HashMap<>()),
                "the second reload must not claim the initialisation");
        assertEquals(Map.of("warm", "yes"),
                FieldStore.getStaticExtField(owner, "CACHE", "Ljava/util/Map;"),
                "and the contents the application accumulated have to survive it");
    }

    /**
     * A plain write counts as the field having a value. Otherwise a field the
     * application assigned before the next reload would be reset by an
     * initialiser that thought it was still untouched.
     */
    @Test
    void anOrdinaryWriteAlsoCountsAsInitialised() {
        String owner = "demo/Written";
        assertFalse(FieldStore.isStaticInitialised(owner, "flag", "Z"));

        FieldStore.putStaticExtField(owner, "flag", "Z", true);

        assertTrue(FieldStore.isStaticInitialised(owner, "flag", "Z"));
        assertFalse(FieldStore.initialiseStaticOnce(owner, "flag", "Z", false),
                "an initialiser must not overwrite a value the application already set");
        assertEquals(true, FieldStore.getStaticExtField(owner, "flag", "Z"));
    }

    /**
     * Writing null removes the entry, so presence in the value map cannot
     * answer "was this ever set". It is the exact shape that would make a
     * field initialise twice.
     */
    @Test
    void aNullWriteStillCountsAsInitialised() {
        String owner = "demo/Nulled";
        FieldStore.putStaticExtField(owner, "name", "Ljava/lang/String;", null);

        assertTrue(FieldStore.isStaticInitialised(owner, "name", "Ljava/lang/String;"),
                "the developer set it to null on purpose and that is its value");
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    static class Fixture {
        static final int MAX = 50;
        static final String NAME = "reclazz";
        static final Map<String, String> CACHE = new ConcurrentHashMap<>();
        static int counter = compute();
        static long spare;

        static int compute() {
            return 7;
        }
    }

    @SuppressWarnings("unused")
    static class Tangled {
        static int A;
        static int B;

        static {
            int seed = seed();
            A = seed;
            B = seed;
        }

        static int seed() {
            return 3;
        }
    }

    @SuppressWarnings("unused")
    static class Branchy {
        static final String MODE = System.getProperty("reclazz.absent") != null ? "on" : "off";
    }

    @SuppressWarnings("unused")
    static class SideEffect {
        static int timesRun;
        static final Map<String, String> LOOKUP = new HashMap<>();

        static {
            timesRun++;
        }
    }

    private static StaticInitialiserSlicer.Plan planFor(Class<?> c, String... keys)
            throws IOException {
        return StaticInitialiserSlicer.planFor(bytecodeOf(c), new LinkedHashSet<>(List.of(keys)));
    }

    /**
     * Builds the companion the way a structural reload does and calls the
     * initialiser it carries: hidden class, nestmate of the target, so the
     * slice keeps the access its original code had.
     */
    private static void runInitialiser(Class<?> target, Set<String> addedKeys) throws Throwable {
        byte[] bytecode = bytecodeOf(target);
        var diff = StructuralAnalyzer.analyze(metadataWithout(bytecode, addedKeys), bytecode);
        assertTrue(diff.getAddedFields().containsAll(addedKeys),
                "the fixture must present these as added: " + diff.getAddedFields());

        var companion = CompanionGenerator.generate(
                internalNameOf(target), bytecode, diff, 1, addedKeys);
        assertTrue(companion.getStaticPlan().hasCode(),
                "nothing was sliced, so this test would pass without running anything");

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(target, MethodHandles.lookup());
        Class<?> companionClass = lookup.defineHiddenClass(companion.getBytecode(), true,
                MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();

        MethodHandles.privateLookupIn(companionClass, lookup)
                .findStatic(companionClass, StaticInitialiserSlicer.INIT_METHOD,
                        MethodType.methodType(void.class))
                .invokeExact();
    }

    /**
     * The class as it was before the reload: its own shape with the fields
     * under test taken out, so the analyser reports exactly those as added.
     */
    private static TransformContext.ClassMetadata metadataWithout(byte[] bytecode,
                                                                   Set<String> addedKeys) {
        List<TransformContext.MethodSig> methods = new ArrayList<>();
        List<TransformContext.FieldSig> fields = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, ClassReader.SKIP_CODE);
        for (var m : node.methods) {
            methods.add(new TransformContext.MethodSig(m.name, m.desc, m.access));
        }
        for (var f : node.fields) {
            if (addedKeys.contains(f.name + ":" + f.desc)) continue;
            fields.add(new TransformContext.FieldSig(f.name, f.desc, f.access));
        }
        return new TransformContext.ClassMetadata(methods, fields, 0, node.superName, Set.of());
    }

    private static String internalNameOf(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    private static byte[] bytecodeOf(Class<?> c) throws IOException {
        try (InputStream in = c.getClassLoader()
                .getResourceAsStream(internalNameOf(c) + ".class")) {
            assertNotNull(in, "cannot read " + c.getName());
            return in.readAllBytes();
        }
    }

    static {
        // Referenced so the fixtures are initialised by the JVM before any
        // test asks whether their static blocks ran again.
        assertEquals(50, Fixture.MAX);
        assertEquals("off", Branchy.MODE);
        assertNotNull(SideEffect.LOOKUP);
        assertEquals(Opcodes.ASM9, Opcodes.ASM9);
    }
}
