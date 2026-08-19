/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transforming a class twice must be the same as transforming it once.
 *
 * <p>The transformer runs during {@code redefineClasses} as well as at load
 * time, and the per-method superclass salvage hands {@code redefineClasses} a
 * payload that is already transformed, because the old bodies it splices in
 * only exist in transformed form. Without this property the second pass
 * renames every trampoline over its own target and injects the
 * infrastructure fields twice, and the JVM rejects the class with
 * {@code ClassFormatError: duplicate field}. That rejection is exactly what
 * the pre-1.0.34 constructor-refresh path had to route around by insisting
 * on raw bytes.
 */
class TransformerIdempotencyTest extends TransformTestBase {

    private static final String SOURCE =
            "public class IdempotentTarget {\n" +
            "    private int counter;\n" +
            "    public String greet() { return \"hello-\" + counter; }\n" +
            "    public static int twice(int x) { return x * 2; }\n" +
            "}";

    /**
     * transform(transform(x)) has to equal transform(x) byte for byte. The
     * transformer signals "no change" by returning null, as the
     * ClassFileTransformer contract defines it, so pass-through means null
     * on input that already carries the transform.
     */
    @Test
    void aSecondTransformPassesTheFirstOnesOutputThroughUntouched() {
        TransformContext context = new TransformContext();
        context.addWatched("IdempotentTarget");
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));

        byte[] raw = compile(new SourceFile("IdempotentTarget", SOURCE)).get("IdempotentTarget");
        byte[] once;
        byte[] again;
        try {
            once = transformer.transform(getClass().getClassLoader(),
                    "IdempotentTarget", null, null, raw);
            assertNotNull(once, "the first transform must produce output");

            again = transformer.transform(getClass().getClassLoader(),
                    "IdempotentTarget", null, null, once);
        } catch (Exception e) {
            fail("transform threw: " + e);
            return;
        }

        assertNull(again,
                "already-transformed input must pass through untouched (null means "
                + "'use the buffer as it is'), otherwise the trampolines are renamed "
                + "over their own targets and the fields are injected twice");
    }

    /** The marker read is the injected field, not anything reflection would hide. */
    @Test
    void theMarkerIsTheInjectedLookupField() {
        byte[] raw = compile(new SourceFile("IdempotentTarget", SOURCE)).get("IdempotentTarget");
        assertFalse(ReclazzTransformer.isAlreadyTransformed(raw),
                "a freshly compiled class carries no __reclazz$lookup field");

        Map<String, byte[]> transformed = compileAndTransform(
                new SourceFile("IdempotentTarget", SOURCE));
        assertTrue(ReclazzTransformer.isAlreadyTransformed(transformed.get("IdempotentTarget")),
                "the transformer's own output carries the field it injects");
    }

    /** Pass-through is only safe if what passed through still runs. */
    @Test
    void aTrampolinedClassStaysValidAndServesItsMethods() throws Exception {
        Map<String, byte[]> transformed = compileAndTransform(
                new SourceFile("IdempotentRuns", SOURCE.replace("IdempotentTarget", "IdempotentRuns")));

        Map<String, byte[]> loadMap = new LinkedHashMap<>(transformed);
        Class<?> cls = defineAndLoad(loadMap, "IdempotentRuns");
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method greet = cls.getDeclaredMethod("greet");
        assertEquals("hello-0", greet.invoke(instance));
        assertEquals(6, invokeStatic(cls, "twice", 3));
    }
}
