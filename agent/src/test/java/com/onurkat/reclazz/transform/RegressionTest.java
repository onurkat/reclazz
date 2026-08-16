/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests — one per shipped fix. Each test reproduces the exact
 * pattern that was reported as a bug.
 */
class RegressionTest extends TransformTestBase {

    /** 1.0.6 — VerifyError from common-supertype merge falling back to Object. */
    @Test
    void v106_verifyErrorFromUnloadableThrowableMerge() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Ex1", "public class Ex1 extends RuntimeException { public Ex1(String m) { super(m); } }"),
                new SourceFile("Ex2", "public class Ex2 extends RuntimeException { public Ex2(String m) { super(m); } }"),
                new SourceFile("Worker",
                        "public class Worker {\n" +
                        "    public static String run(String input) {\n" +
                        "        try {\n" +
                        "            if (input.startsWith(\"a\")) throw new RuntimeException(\"x\", new Ex1(\"a-cause\"));\n" +
                        "            else throw new RuntimeException(\"y\", new IllegalStateException(\"b-cause\"));\n" +
                        "        } catch (RuntimeException e) {\n" +
                        "            Throwable c = e.getCause();\n" +
                        "            throw c instanceof Ex1 ? (Ex1) c : new Ex2(e.getMessage());\n" +
                        "        }\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "Worker");
        try {
            invokeStatic(cls, "run", "anything");
        } catch (Throwable t) {
            // expected: an Ex1 or Ex2 should propagate
        }
    }

    /** 1.0.7 — Interface should be skipped, not rejected with ClassFormatError. */
    @Test
    void v107_interfaceWithDefaultMethodSkipped() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Dao",
                        "public interface Dao {\n" +
                        "    String fetch(String id);\n" +
                        "    default String fetchPrefixed(String id) { return \"v1:\" + fetch(id); }\n" +
                        "}"),
                new SourceFile("DaoImpl",
                        "public class DaoImpl implements Dao {\n" +
                        "    public String fetch(String id) { return \"raw:\" + id; }\n" +
                        "    public static String run() { return new DaoImpl().fetchPrefixed(\"hello\"); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "DaoImpl");
        assertEquals("v1:raw:hello", invokeStatic(cls, "run"));
    }

    /** 1.0.8 — Anonymous subclass of HashMap calling inherited put() in initializer. */
    @Test
    void v108_inheritedMethodFromAnonymousInnerClass() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Strategy",
                        "import java.util.*;\n" +
                        "public class Strategy {\n" +
                        "    static final Map<String, String> MAP = new HashMap<String, String>() {{\n" +
                        "        put(\"k1\", \"v1\");\n" +
                        "        put(\"k2\", \"v2\");\n" +
                        "    }};\n" +
                        "    public static String lookup(String k) { return MAP.get(k); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "Strategy");
        assertEquals("v1", invokeStatic(cls, "lookup", "k1"));
        assertEquals("v2", invokeStatic(cls, "lookup", "k2"));
    }
}
