/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for applying CONSTRUCTOR body changes via
 * redefineClasses on a transformed class.
 *
 * Live finding: the agent's registered ReclazzTransformer also runs during
 * redefineClasses. Feeding it bytes that were ALREADY doTransform'ed used to
 * make it inject the infrastructure twice → ClassFormatError (duplicate
 * field) → "Constructor-body refresh skipped". The ordinary reload path
 * still passes RAW compiled bytes and lets the registered transformer do its
 * work exactly like at load time; the transformer is additionally idempotent
 * now (it recognises its own output by the injected __reclazz$lookup field
 * and passes it through), because the superclass salvage has to redefine a
 * payload that is already transformed: the old bodies it splices in only
 * exist in transformed form.
 */
class ConstructorBodyRedefineTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private static final String V1 =
            "public class CtorClass {\n" +
            "    private final String tag;\n" +
            "    public CtorClass() { this.tag = \"old-ctor\"; }\n" +
            "    public String tag() { return tag; }\n" +
            "}";

    private static final String V2 =
            "public class CtorClass {\n" +
            "    private final String tag;\n" +
            "    public CtorClass() { this.tag = \"new-ctor\"; }\n" +
            "    public String tag() { return tag; }\n" +
            "}";

    @Test
    void rawBytesRedefineAppliesCtorBody_withRegisteredTransformer() throws Exception {
        // Register a live transformer the way the agent does, so redefinition
        // re-transforms raw bytes exactly like class load did. The context
        // carries the record of that class load, as the agent's single context
        // does: a redefinition of a class the agent never instrumented is left
        // alone, because its shape cannot be changed after the fact.
        TransformContext context = new TransformContext();
        context.addWatched("CtorClass");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        Class<?> cls = loadThrough(transformer, "CtorClass", V1);

        Object oldInstance = cls.getDeclaredConstructor().newInstance();
        Method tag = cls.getDeclaredMethod("tag");
        assertEquals("old-ctor", tag.invoke(oldInstance));

        instrumentation.addTransformer(transformer, true);
        try {
            Map<String, byte[]> v2raw = compile(new SourceFile("CtorClass", V2));
            instrumentation.redefineClasses(new ClassDefinition(cls, v2raw.get("CtorClass")));

            Object fresh = cls.getDeclaredConstructor().newInstance();
            assertEquals("new-ctor", tag.invoke(fresh),
                    "new instances must be built by the NEW constructor");
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    /**
     * Pre-transformed bytes used to be rejected here (double infrastructure
     * injection, ClassFormatError), which is why the reloader insists on raw
     * bytes for the ordinary path. The superclass salvage cannot use raw
     * bytes: its payload carries spliced, already-transformed old bodies. So
     * the registered transformer now recognises its own output and passes it
     * through, and this redefinition must succeed with the constructor body
     * applied. Losing the pass-through brings back the ClassFormatError, on
     * the salvage path, during redefineClasses, on a live server.
     */
    @Test
    void preTransformedBytesPassThroughAndTheCtorBodyStillApplies() throws Exception {
        TransformContext context = new TransformContext();
        context.addWatched("CtorClass2");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        Class<?> cls = loadThrough(transformer, "CtorClass2",
                V1.replace("CtorClass", "CtorClass2"));
        Method tag = cls.getDeclaredMethod("tag");
        assertEquals("old-ctor", tag.invoke(cls.getDeclaredConstructor().newInstance()));

        instrumentation.addTransformer(transformer, true);
        try {
            Map<String, byte[]> v2transformed = compileAndTransform(new SourceFile("CtorClass2",
                    V2.replace("CtorClass", "CtorClass2")));
            instrumentation.redefineClasses(
                    new ClassDefinition(cls, v2transformed.get("CtorClass2")));

            Object fresh = cls.getDeclaredConstructor().newInstance();
            assertEquals("new-ctor", tag.invoke(fresh),
                    "the pass-through payload's constructor body must reach new instances");
        } finally {
            instrumentation.removeTransformer(transformer);
        }
    }

    /**
     * Compile, transform through the given transformer (which records the
     * class in its context exactly like class loading does) and load. The
     * transform has to see the RAW bytes: since the transformer became
     * idempotent it passes its own output through without recording, so
     * rebuilding the context from already-transformed bytes records nothing.
     */
    private static Class<?> loadThrough(ReclazzTransformer transformer,
                                        String name, String source) throws Exception {
        byte[] raw = compile(new SourceFile(name, source)).get(name);
        byte[] transformed = transformer.transform(
                ConstructorBodyRedefineTest.class.getClassLoader(), name, null, null, raw);
        assertNotNull(transformed, "the load-time transform must produce output");
        Map<String, byte[]> loadMap = new java.util.LinkedHashMap<>();
        loadMap.put(name, transformed);
        return defineAndLoad(loadMap, name);
    }
}
