/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.ReflectionBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bridge only matters if call sites actually reach it. This drives the
 * transformer over compiled bytecode and then runs the result, so it fails
 * for the two separate reasons it can: the rewrite not happening, and the
 * rewrite happening but producing something that does not run.
 *
 * Written because {@code getMethods()} was not rewritten for a long time
 * while {@code getDeclaredMethods()} was, which is invisible from the
 * outside until a framework picks the wrong one.
 */
class ReflectionRewriteCoverageTest extends TransformTestBase {

    /** Calls each reflection form and reports what it saw. */
    private static final String CALLER_SRC =
            "import java.lang.reflect.*;\n" +
            "public class Caller {\n" +
            "    public static int declaredCount(Class<?> c) { return c.getDeclaredMethods().length; }\n" +
            "    public static int publicCount(Class<?> c) { return c.getMethods().length; }\n" +
            "    public static int publicFieldCount(Class<?> c) { return c.getFields().length; }\n" +
            "    public static String publicLookup(Class<?> c, String n) throws Exception {\n" +
            "        return c.getMethod(n).getName();\n" +
            "    }\n" +
            "}";

    /** The class whose members get "added" by a structural reload. */
    private static final String TARGET_SRC =
            "public class Target {\n" +
            "    public String original() { return \"original\"; }\n" +
            "    public String added() { return \"added\"; }\n" +
            "    public String addedField = \"\";\n" +
            "}";

    private String targetKey;

    @AfterEach
    void clearState() {
        if (targetKey != null) {
            ReflectionBridge.replaceClassState(targetKey, List.of(), List.of());
        }
    }

    @Test
    void everyRewrittenFormSeesAStructurallyAddedMember() throws Exception {
        Map<String, byte[]> compiled = compile(
                new SourceFile("Caller", CALLER_SRC),
                new SourceFile("Target", TARGET_SRC));

        // Run the reflection interception over the caller exactly as the agent
        // does at load time.
        ReflectionInterceptTransformer transformer = new ReflectionInterceptTransformer();
        byte[] rewritten = transformer.transform(
                null, "Caller", null, null, compiled.get("Caller"));
        assertNotNull(rewritten, "the caller contains reflection calls and should have been rewritten");

        Map<String, byte[]> classes = new java.util.HashMap<>(compiled);
        classes.put("Caller", rewritten);
        SharedLoader loader = sharedLoader(classes);
        Class<?> caller = loader.load("Caller");
        Class<?> target = loader.load("Target");

        targetKey = target.getName().replace('.', '/');

        int declaredBefore = (int) invokeStatic(caller, "declaredCount", target);
        int publicBefore = (int) invokeStatic(caller, "publicCount", target);
        int fieldsBefore = (int) invokeStatic(caller, "publicFieldCount", target);

        // Register "added" members the way StructuralReloader does.
        Method added = target.getDeclaredMethod("added");
        ReflectionBridge.replaceClassState(targetKey,
                List.of(added), List.of(target.getDeclaredField("addedField")));

        assertEquals(declaredBefore + 1, (int) invokeStatic(caller, "declaredCount", target),
                "getDeclaredMethods() call site did not go through the bridge");
        assertEquals(publicBefore + 1, (int) invokeStatic(caller, "publicCount", target),
                "getMethods() call site did not go through the bridge");
        assertEquals(fieldsBefore + 1, (int) invokeStatic(caller, "publicFieldCount", target),
                "getFields() call site did not go through the bridge");
        assertEquals("added", invokeStatic(caller, "publicLookup", target, "added"),
                "getMethod() call site did not go through the bridge");
    }

    /** A class with no reflection in it must be left exactly alone. */
    @Test
    void aClassWithoutReflectionIsNotRewritten() throws Exception {
        Map<String, byte[]> compiled = compile(new SourceFile("Plain",
                "public class Plain { public int add(int a, int b) { return a + b; } }"));

        byte[] result = new ReflectionInterceptTransformer()
                .transform(null, "Plain", null, null, compiled.get("Plain"));

        assertNull(result, "returning null leaves the original bytes in place");
    }
}
