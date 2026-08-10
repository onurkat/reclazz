package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Class structure features: nested classes, enums, generics, varargs, etc. */
class ClassMembersTest extends TransformTestBase {

    @Test
    void staticInitializer() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    static int X;\n" +
                        "    static { X = 7 * 6; }\n" +
                        "    public static int run() { return X; }\n" +
                        "}")
        );
        assertEquals(42, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void multipleStaticFields() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    static int A = 1;\n" +
                        "    static int B = 2;\n" +
                        "    static int C = 3;\n" +
                        "    public static int run() { return A + B + C; }\n" +
                        "}")
        );
        assertEquals(6, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void genericMethod() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public static <X> X first(List<X> list) { return list.get(0); }\n" +
                        "    public static String run() { return first(Arrays.asList(\"a\", \"b\")); }\n" +
                        "}")
        );
        assertEquals("a", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void varargs() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int sum(int... xs) { int s = 0; for (int x : xs) s += x; return s; }\n" +
                        "    public static int run() { return sum(1, 2, 3, 4, 5); }\n" +
                        "}")
        );
        assertEquals(15, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void staticNestedClass() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    static class Pair { final int a, b; Pair(int a, int b) { this.a = a; this.b = b; } int sum() { return a + b; } }\n" +
                        "    public static int run() { return new Pair(3, 4).sum(); }\n" +
                        "}")
        );
        assertEquals(7, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void innerClass() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    private int base = 10;\n" +
                        "    class Inner { int compute(int x) { return base + x; } }\n" +
                        "    public int useInner() { return new Inner().compute(5); }\n" +
                        "    public static int run() { return new T().useInner(); }\n" +
                        "}")
        );
        assertEquals(15, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void enumWithMethods() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    enum E {\n" +
                        "        A(1), B(2), C(3);\n" +
                        "        final int v;\n" +
                        "        E(int v) { this.v = v; }\n" +
                        "        int square() { return v * v; }\n" +
                        "    }\n" +
                        "    public static int run() { return E.C.square(); }\n" +
                        "}")
        );
        assertEquals(9, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void overloadedMethods() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int f(int x) { return x; }\n" +
                        "    public static int f(int x, int y) { return x + y; }\n" +
                        "    public static String f(String x) { return \"s:\" + x; }\n" +
                        "    public static String run() { return f(1) + \",\" + f(2, 3) + \",\" + f(\"hi\"); }\n" +
                        "}")
        );
        assertEquals("1,5,s:hi", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void recursiveMethod() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static long fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }\n" +
                        "    public static long run() { return fib(10); }\n" +
                        "}")
        );
        assertEquals(55L, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void primitiveReturnTypes() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static byte    b() { return (byte) 1; }\n" +
                        "    public static short   s() { return (short) 2; }\n" +
                        "    public static int     i() { return 3; }\n" +
                        "    public static long    l() { return 4L; }\n" +
                        "    public static float   f() { return 5.0f; }\n" +
                        "    public static double  d() { return 6.0; }\n" +
                        "    public static char    c() { return 'a'; }\n" +
                        "    public static boolean z() { return true; }\n" +
                        "    public static void    v() { }\n" +
                        "    public static String  run() {\n" +
                        "        v();\n" +
                        "        return \"\" + b() + s() + i() + l() + f() + d() + c() + z();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("12345.06.0atrue", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }
}
