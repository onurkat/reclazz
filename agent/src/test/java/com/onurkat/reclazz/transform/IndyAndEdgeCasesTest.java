package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** invokedynamic-heavy patterns and bytecode-shape edge cases. */
class IndyAndEdgeCasesTest extends TransformTestBase {

    /** Java 9+ string concat compiles to invokedynamic to StringConcatFactory. */
    @Test
    void stringConcatIndy() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run(String name, int age) { return \"Hello \" + name + \", you are \" + age; }\n" +
                        "}")
        );
        assertEquals("Hello Onur, you are 30",
                invokeStatic(defineAndLoad(classes, "T"), "run", "Onur", 30));
    }

    @Test
    void primitivesInStringConcat() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run() { return \"\" + 1 + \"|\" + 2.5 + \"|\" + true + \"|\" + 'x' + \"|\" + 100L; }\n" +
                        "}")
        );
        assertEquals("1|2.5|true|x|100", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void longMethod() {
        StringBuilder body = new StringBuilder();
        body.append("int s = 0;\n");
        for (int i = 0; i < 200; i++) {
            body.append("s += ").append(i).append(";\n");
        }
        body.append("return s;\n");
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int run() {\n" + body + "    }\n" +
                        "}")
        );
        assertEquals(200 * 199 / 2, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void manyMethodsInOneClass() {
        StringBuilder src = new StringBuilder("public class T {\n");
        for (int i = 0; i < 100; i++) {
            src.append("    public static int m").append(i).append("() { return ").append(i).append("; }\n");
        }
        src.append("    public static int run() {\n");
        src.append("        int s = 0;\n");
        for (int i = 0; i < 100; i++) {
            src.append("        s += m").append(i).append("();\n");
        }
        src.append("        return s;\n    }\n}\n");
        Map<String, byte[]> classes = compileAndTransform(new SourceFile("T", src.toString()));
        assertEquals(100 * 99 / 2, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void deeplyNestedAnonymousClasses() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.concurrent.Callable;\n" +
                        "public class T {\n" +
                        "    public static int run() throws Exception {\n" +
                        "        Callable<Integer> outer = new Callable<>() {\n" +
                        "            public Integer call() throws Exception {\n" +
                        "                Callable<Integer> inner = new Callable<>() {\n" +
                        "                    public Integer call() { return 21; }\n" +
                        "                };\n" +
                        "                return inner.call() * 2;\n" +
                        "            }\n" +
                        "        };\n" +
                        "        return outer.call();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(42, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void exceptionRethrow() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run(boolean fail) {\n" +
                        "        try {\n" +
                        "            if (fail) throw new RuntimeException(\"boom\");\n" +
                        "            return \"ok\";\n" +
                        "        } catch (RuntimeException e) {\n" +
                        "            throw new IllegalStateException(\"wrapped: \" + e.getMessage(), e);\n" +
                        "        }\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals("ok", invokeStatic(cls, "run", false));
    }

    @Test
    void arrayHandling() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int run() {\n" +
                        "        int[] a = {1, 2, 3, 4, 5};\n" +
                        "        int[][] m = {{1, 2}, {3, 4}};\n" +
                        "        return a[0] + a[4] + m[1][0];\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(9, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void stringSwitch() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int run(String x) {\n" +
                        "        switch (x) {\n" +
                        "            case \"one\": return 1;\n" +
                        "            case \"two\": return 2;\n" +
                        "            default: return -1;\n" +
                        "        }\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals(1, invokeStatic(cls, "run", "one"));
        assertEquals(-1, invokeStatic(cls, "run", "nope"));
    }

    @Test
    void nullCheckAndIfElse() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run(String x) {\n" +
                        "        if (x == null) return \"null\";\n" +
                        "        if (x.isEmpty()) return \"empty\";\n" +
                        "        return \"len:\" + x.length();\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals("null", invokeStatic(cls, "run", new Object[]{null}));
        assertEquals("empty", invokeStatic(cls, "run", ""));
        assertEquals("len:5", invokeStatic(cls, "run", "hello"));
    }
}
