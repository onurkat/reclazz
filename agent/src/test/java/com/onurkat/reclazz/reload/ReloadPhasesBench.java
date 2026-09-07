/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.agent.ClassReloader;
import com.onurkat.reclazz.transform.ReclazzTransformer;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformTestBase;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Not a test: where the time of a body-only reload goes, per class, and what
 * one redefineClasses call with N definitions costs against N calls.
 * <pre>
 * ./gradlew :agent:test --tests '*ReloadPhasesBench*' -Preclazz.bench.out=/tmp/phases.txt
 * </pre>
 */
class ReloadPhasesBench extends TransformTestBase {

    private static final int CLASSES = 30;

    @Test
    @EnabledIfSystemProperty(named = "reclazz.bench.out", matches = ".+")
    void measure() throws Exception {
        Instrumentation inst = ByteBuddyAgent.install();
        TransformContext context = new TransformContext();
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        StringBuilder out = new StringBuilder();

        // Load CLASSES transformed classes.
        Map<String, byte[]> v1 = new LinkedHashMap<>();
        Map<String, byte[]> v2 = new LinkedHashMap<>();
        for (int i = 0; i < CLASSES; i++) {
            String name = "Bench" + i;
            context.addWatched(name);
            v1.put(name, compile(new SourceFile(name, src(name, "v1"))).get(name));
            v2.put(name, compile(new SourceFile(name, src(name, "v2"))).get(name));
        }
        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        for (var e : v1.entrySet()) {
            loadMap.put(e.getKey(), transformer.transform(TransformTestBase.class.getClassLoader(),
                    e.getKey(), null, null, e.getValue()));
        }
        List<Class<?>> classes = new ArrayList<>();
        for (String name : v1.keySet()) classes.add(defineAndLoad(loadMap, name));
        for (Class<?> c : classes) c.getMethod("hello").invoke(c.getDeclaredConstructor().newInstance());

        StructuralReloader reloader = new StructuralReloader(inst, context, AgentConfig.parse(null), null);
        reloader.setTransformer(transformer);
        inst.addTransformer(transformer, true);
        try {
            for (int round = 0; round < 3; round++) {
                // Whole reload per class, as the batch path does it.
                long t0 = System.nanoTime();
                for (var e : (round % 2 == 0 ? v2 : v1).entrySet()) {
                    ClassReloader.ReloadResult r = reloader.reload(e.getKey(), e.getValue());
                    if (!r.isSuccess()) throw new AssertionError(r.getError());
                }
                long whole = (System.nanoTime() - t0) / 1_000_000;

                // The same reloads inside the batch bracket: one redefinition call.
                long b0 = System.nanoTime();
                reloader.beginBatch();
                try {
                    for (var e : (round % 2 == 0 ? v1 : v2).entrySet()) {
                        ClassReloader.ReloadResult r = reloader.reload(e.getKey(), e.getValue());
                        if (!r.isSuccess()) throw new AssertionError(r.getError());
                    }
                } finally {
                    reloader.endBatch();
                }
                long batchedWhole = (System.nanoTime() - b0) / 1_000_000;

                // Phases, on fresh structural inputs (same version again is a no-op diff but the work is the same).
                long tDiff = 0, tGen = 0;
                for (var e : v2.entrySet()) {
                    TransformContext.ClassMetadata old = context.getMetadata(e.getKey());
                    long a = System.nanoTime();
                    StructuralAnalyzer.StructuralDiff diff = StructuralAnalyzer.analyze(old, e.getValue());
                    long b = System.nanoTime();
                    CompanionGenerator.generate(e.getKey(), e.getValue(), diff, 99 + round);
                    long c = System.nanoTime();
                    tDiff += b - a; tGen += c - b;
                }

                // Raw redefinition: N calls vs one call with N definitions. The
                // payload is the transformed v1/v2 (same shape), through the registered transformer.
                List<ClassDefinition> defs = new ArrayList<>();
                Map<String, byte[]> payload = round % 2 == 0 ? v1 : v2;
                for (Class<?> c : classes) defs.add(new ClassDefinition(c, payload.get(c.getName())));
                long r0 = System.nanoTime();
                for (ClassDefinition d : defs) inst.redefineClasses(d);
                long sequential = (System.nanoTime() - r0) / 1_000_000;
                long r1 = System.nanoTime();
                inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
                long batched = (System.nanoTime() - r1) / 1_000_000;

                out.append(String.format("round=%d classes=%d wholeReloadPerClass=%dms wholeReloadBatched=%dms | diff=%dms companionGen=%dms | rawRedefine sequential=%dms oneCall=%dms%n",
                        round, CLASSES, whole, batchedWhole, tDiff / 1_000_000, tGen / 1_000_000, sequential, batched));
            }
        } finally {
            inst.removeTransformer(transformer);
        }
        Files.writeString(Path.of(System.getProperty("reclazz.bench.out")), out.toString());
    }

    private static String src(String name, String v) {
        return "public class " + name + " {\n"
                + "    private int counter;\n"
                + "    public String hello() { counter++; return \"" + v + "\" + counter; }\n"
                + "    public String other(int a, String b) { return b + a + \"" + v + "\"; }\n"
                + "    public static int twice(int x) { return x * 2; }\n"
                + "}";
    }
}
