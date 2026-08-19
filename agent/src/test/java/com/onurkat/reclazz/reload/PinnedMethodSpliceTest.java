/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.TransformTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The splice is what makes pinning a method honest. The first per-method
 * salvage skipped the entangled method in the companion and left the NEW
 * body in the redefine payload; the transformer renamed that body over
 * {@code __reclazz$v0$...}, the trampoline's fallback dispatched into it,
 * and the body's call to a member only the new superclass provides killed
 * the application thread with {@code UnsupportedOperationException:
 * Reclazz: method not found: Service.yalnizB2}. These tests hold the fix:
 * after the splice, the loaded class's fallback body for a pinned method IS
 * the previous implementation, and a splice that cannot prove it has that
 * implementation refuses.
 */
class PinnedMethodSpliceTest extends TransformTestBase {

    private static final String V1 =
            "public class SpliceTarget {\n" +
            "    public String a() { return \"a1\"; }\n" +
            "    public String c() { return \"old-c\"; }\n" +
            "}";

    private static final String V2 =
            "public class SpliceTarget {\n" +
            "    public String a() { return \"a2\"; }\n" +
            "    public String c() { return \"new-c\"; }\n" +
            "}";

    /**
     * The pinned method serves its previous body while the method beside it
     * serves its new one, from the same spliced class file. Asserted by
     * loading the spliced result and calling both, which is the check the
     * crash would have failed.
     */
    @Test
    void thePinnedMethodKeepsItsOldBodyAndTheRestStayNew() throws Exception {
        byte[] lastKnownGood = compileAndTransform(new SourceFile("SpliceTarget", V1))
                .get("SpliceTarget");
        byte[] newPayload = compileAndTransform(new SourceFile("SpliceTarget", V2))
                .get("SpliceTarget");

        PinnedMethodSplice.Result result = PinnedMethodSplice.apply(
                newPayload, lastKnownGood, Set.of("c:()Ljava/lang/String;"));
        assertTrue(result.applied(), "splice refused: " + result.reason());

        Map<String, byte[]> loadMap = new LinkedHashMap<>();
        loadMap.put("SpliceTarget", result.bytecode());
        Class<?> cls = defineAndLoad(loadMap, "SpliceTarget");
        Object instance = cls.getDeclaredConstructor().newInstance();

        Method a = cls.getDeclaredMethod("a");
        Method c = cls.getDeclaredMethod("c");
        assertEquals("a2", a.invoke(instance), "the unpinned method carries the new body");
        assertEquals("old-c", c.invoke(instance),
                "the pinned method must serve the implementation it had; serving the "
                + "new body is the exact failure that crashed live");
    }

    /**
     * A cache that does not hold the previous implementation in transformed
     * form has nothing to pin to. Raw compiled bytes have no
     * {@code __reclazz$v0$} copy, so they are indistinguishable from a class
     * the transformer never saw, and the splice must refuse rather than
     * fabricate a body.
     */
    @Test
    void aCacheWithoutTheTransformedOldBodyRefuses() {
        byte[] rawOldBytes = compile(new SourceFile("SpliceTarget", V1)).get("SpliceTarget");
        byte[] newPayload = compileAndTransform(new SourceFile("SpliceTarget", V2))
                .get("SpliceTarget");

        PinnedMethodSplice.Result result = PinnedMethodSplice.apply(
                newPayload, rawOldBytes, Set.of("c:()Ljava/lang/String;"));

        assertFalse(result.applied());
        assertTrue(result.reason().contains("c"),
                "the refusal has to name the method it cannot pin: " + result.reason());
    }

    /**
     * A method the previous version never had cannot keep "the implementation
     * it had", because there is none. This is the added-and-entangled case,
     * and it has to refuse so the reloader falls back to refusing the class.
     */
    @Test
    void aMethodTheOldVersionDidNotHaveRefuses() {
        byte[] lastKnownGood = compileAndTransform(new SourceFile("SpliceTarget", V1))
                .get("SpliceTarget");
        byte[] newPayload = compileAndTransform(new SourceFile("SpliceTarget",
                V2.replace("public String c()", "public String d() { return \"d\"; }\n"
                        + "    public String c()")))
                .get("SpliceTarget");

        PinnedMethodSplice.Result result = PinnedMethodSplice.apply(
                newPayload, lastKnownGood, Set.of("d:()Ljava/lang/String;"));

        assertFalse(result.applied());
        assertTrue(result.reason().contains("d"), result.reason());
    }
}
