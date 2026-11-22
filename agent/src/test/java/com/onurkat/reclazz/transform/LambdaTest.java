/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lambdas, method references, and stream operations. */
class LambdaTest extends TransformTestBase {

    @Test
    void simpleLambda() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.function.*;\n" +
                        "public class T {\n" +
                        "    public static int run() {\n" +
                        "        Function<Integer, Integer> f = x -> x * 2;\n" +
                        "        return f.apply(21);\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(42, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void lambdaCapturingLocal() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.function.*;\n" +
                        "public class T {\n" +
                        "    public static int run(int base) {\n" +
                        "        Function<Integer, Integer> f = x -> base + x;\n" +
                        "        return f.apply(10);\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(15, invokeStatic(defineAndLoad(classes, "T"), "run", 5));
    }

    @Test
    void lambdaCapturingThis() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.function.*;\n" +
                        "public class T {\n" +
                        "    private final int n;\n" +
                        "    public T(int n) { this.n = n; }\n" +
                        "    public int compute() {\n" +
                        "        Function<Integer, Integer> f = x -> n * x;\n" +
                        "        return f.apply(3);\n" +
                        "    }\n" +
                        "    public static int run() { return new T(7).compute(); }\n" +
                        "}")
        );
        assertEquals(21, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void staticMethodReference() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.function.*;\n" +
                        "public class T {\n" +
                        "    public static int doubleIt(int x) { return x * 2; }\n" +
                        "    public static int run() {\n" +
                        "        Function<Integer, Integer> f = T::doubleIt;\n" +
                        "        return f.apply(11);\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(22, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void instanceMethodReference() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.function.*;\n" +
                        "public class T {\n" +
                        "    public int instMul(int x) { return x * 5; }\n" +
                        "    public int compute() {\n" +
                        "        Function<Integer, Integer> f = this::instMul;\n" +
                        "        return f.apply(4);\n" +
                        "    }\n" +
                        "    public static int run() { return new T().compute(); }\n" +
                        "}")
        );
        assertEquals(20, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void streamOperation() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "import java.util.stream.*;\n" +
                        "public class T {\n" +
                        "    public static int run() {\n" +
                        "        return IntStream.rangeClosed(1, 5).sum();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(15, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }
    @Test
    void unwatchedConstructorReferenceKeepsItsNativeMetafactory() {
        var classes=compileAndTransform(new SourceFile("NativeReference", """
            import java.util.function.Supplier;
            public class NativeReference {
                public static int run() {Supplier<StringBuilder> value=StringBuilder::new;return value.get().append("ok").length();}
            }
            """));
        var node=new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(classes.get("NativeReference")).accept(node,0);
        var sites=node.methods.stream().flatMap(m->java.util.Arrays.stream(m.instructions.toArray()))
                .filter(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::isInstance)
                .map(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::cast)
                .filter(i->i.bsmArgs.length>1&&i.bsmArgs[1] instanceof org.objectweb.asm.Handle h&&h.getName().equals("<init>"))
                .toList();
        assertEquals(1,sites.size());
        assertEquals("java/lang/invoke/LambdaMetafactory",sites.get(0).bsm.getOwner());
        assertEquals(2,invokeStatic(defineAndLoad(classes,"NativeReference"),"run"));
    }

}
