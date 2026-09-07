/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inside a batch the JVM redefinitions wait for the end and go in one call;
 * the bodies are live from each class's own switch. When the one call is
 * refused, every class gets its own call and its own outcome, so nothing is
 * lost to a neighbour the JVM would not take.
 */
class BatchedRedefinitionTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    /** A constructor is what only the redefinition can change: the companion carries method bodies. */
    private static String source(String name, String version) {
        return "public class " + name + " {\n"
                + "    public final String born;\n"
                + "    public " + name + "() { born = \"" + version + "\"; }\n"
                + "    public String hello() { return \"" + version + "\"; }\n"
                + "}";
    }

    private record Loaded(Class<?> cls, TransformContext context, ReclazzTransformer transformer) {}

    private static Loaded load(String name, TransformContext context, ReclazzTransformer transformer) throws Exception {
        context.addWatched(name);
        byte[] transformed = transformer.transform(TransformTestBase.class.getClassLoader(), name, null, null,
                compile(new SourceFile(name, source(name, "v1"))).get(name));
        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put(name, transformed);
        return new Loaded(defineAndLoad(loadMap, name), context, transformer);
    }

    private static String born(Class<?> cls) throws Exception {
        Object o = cls.getDeclaredConstructor().newInstance();
        return (String) cls.getField("born").get(o);
    }

    private static String hello(Class<?> cls) throws Exception {
        return (String) cls.getMethod("hello").invoke(cls.getDeclaredConstructor().newInstance());
    }

    @Test
    void redefinitionsWaitForTheEndOfTheBatchAndThenAllLand() throws Exception {
        TransformContext context = new TransformContext();
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        Loaded a = load("BatchA", context, transformer);
        Loaded b = load("BatchB", context, transformer);
        StructuralReloader reloader = new StructuralReloader(instrumentation, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);
        instrumentation.addTransformer(transformer, true);
        try {
            reloader.beginBatch();
            ClassReloader.ReloadResult ra = reloader.reload("BatchA",
                    compile(new SourceFile("BatchA", source("BatchA", "v2"))).get("BatchA"));
            ClassReloader.ReloadResult rb = reloader.reload("BatchB",
                    compile(new SourceFile("BatchB", source("BatchB", "v2"))).get("BatchB"));
            assertTrue(ra.isSuccess() && rb.isSuccess(), ra.getError() + " / " + rb.getError());

            assertEquals("v2", hello(a.cls()), "bodies are live from the switch");
            assertEquals("v2", hello(b.cls()));
            assertEquals("v1", born(a.cls()), "the constructor waits for the batch's redefinition");
            assertEquals("v1", born(b.cls()));

            reloader.endBatch();
            assertEquals("v2", born(a.cls()), "one call at the end, and both constructors are new");
            assertEquals("v2", born(b.cls()));
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    @Test
    void aRefusedDefinitionCostsOnlyItsOwnClass() throws Exception {
        TransformContext context = new TransformContext();
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        Loaded good = load("BatchGood", context, transformer);
        Loaded other = load("BatchOther", context, transformer);
        StructuralReloader reloader = new StructuralReloader(instrumentation, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);
        instrumentation.addTransformer(transformer, true);
        List<String> outcomes = new ArrayList<>();
        try {
            byte[] goodV2 = transformer.doTransform("BatchGood",
                    compile(new SourceFile("BatchGood", source("BatchGood", "v2"))).get("BatchGood"),
                    TransformTestBase.class.getClassLoader());
            // A payload the JVM will not take for BatchOther: BatchGood's bytes
            // under BatchOther's name is a class name mismatch.
            byte[] wrongForOther = goodV2;

            reloader.beginBatch();
            reloader.redefine(good.cls(), goodV2, outcome -> outcomes.add("good:" + describe(outcome)));
            reloader.redefine(other.cls(), wrongForOther, outcome -> outcomes.add("other:" + describe(outcome)));
            assertTrue(outcomes.isEmpty(), "nothing happens before the end of the batch");
            reloader.endBatch();

            assertEquals(2, outcomes.size(), outcomes.toString());
            assertTrue(outcomes.contains("good:applied"), "the good one landed on its own after the one call was refused: " + outcomes);
            assertTrue(outcomes.stream().anyMatch(o -> o.startsWith("other:") && !o.equals("other:applied")),
                    "the bad one got its own outcome: " + outcomes);
            assertEquals("v2", born(good.cls()), "and the good one's redefinition really took");
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    private static String describe(StructuralReloader.RedefineOutcome outcome) {
        if (outcome.applied()) return "applied";
        if (outcome.refused()) return "refused";
        return "failed:" + outcome.failure().getClass().getSimpleName();
    }
}
