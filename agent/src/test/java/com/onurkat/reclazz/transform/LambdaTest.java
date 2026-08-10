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
}
