/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cross-class call sites where both caller and callee are watched. */
class MultiClassTest extends TransformTestBase {

    @Test
    void instanceCallAcrossClasses() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Service",
                        "public class Service {\n" +
                        "    public String greet(String name) { return \"hi \" + name; }\n" +
                        "}"),
                new SourceFile("Caller",
                        "public class Caller {\n" +
                        "    public static String run() { return new Service().greet(\"world\"); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "Caller");
        assertEquals("hi world", invokeStatic(cls, "run"));
    }

    @Test
    void staticCallAcrossClasses() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Util",
                        "public class Util {\n" +
                        "    public static int doubleIt(int x) { return x * 2; }\n" +
                        "}"),
                new SourceFile("Caller",
                        "public class Caller {\n" +
                        "    public static int run() { return Util.doubleIt(21); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "Caller");
        assertEquals(42, invokeStatic(cls, "run"));
    }

    @Test
    void crossClassFieldAccess() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Holder",
                        "public class Holder {\n" +
                        "    public int value = 7;\n" +
                        "}"),
                new SourceFile("Caller",
                        "public class Caller {\n" +
                        "    public static int run() {\n" +
                        "        Holder h = new Holder();\n" +
                        "        h.value = 42;\n" +
                        "        return h.value;\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "Caller");
        assertEquals(42, invokeStatic(cls, "run"));
    }

    @Test
    void chainedCallsAcrossThreeClasses() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("A",
                        "public class A {\n" +
                        "    public int valA() { return 1; }\n" +
                        "}"),
                new SourceFile("B",
                        "public class B {\n" +
                        "    public int valB(A a) { return a.valA() + 10; }\n" +
                        "}"),
                new SourceFile("C",
                        "public class C {\n" +
                        "    public static int run() { return new B().valB(new A()); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "C");
        assertEquals(11, invokeStatic(cls, "run"));
    }
}
