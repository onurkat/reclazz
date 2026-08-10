package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Control-flow constructs the transformer must preserve. */
class ControlFlowTest extends TransformTestBase {

    @Test
    void tryFinally() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        StringBuilder sb = new StringBuilder();\n" +
                        "        try { sb.append(\"a\"); throw new RuntimeException(); }\n" +
                        "        catch (Exception e) { sb.append(\"b\"); }\n" +
                        "        finally { sb.append(\"c\"); }\n" +
                        "        return sb.toString();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("abc", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void tryWithResources() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.io.*;\n" +
                        "public class T {\n" +
                        "    public static int run() throws IOException {\n" +
                        "        try (StringReader r = new StringReader(\"hi\")) {\n" +
                        "            return r.read();\n" +
                        "        }\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals((int) 'h', invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void synchronizedMethod() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public synchronized int sync() { return 42; }\n" +
                        "    public static int run() { return new T().sync(); }\n" +
                        "}")
        );
        assertEquals(42, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void synchronizedBlock() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int run() {\n" +
                        "        Object lock = new Object();\n" +
                        "        synchronized (lock) { return 7; }\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(7, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void switchExpression() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run(int x) {\n" +
                        "        return switch (x) {\n" +
                        "            case 1 -> \"one\";\n" +
                        "            case 2 -> \"two\";\n" +
                        "            default -> \"other\";\n" +
                        "        };\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals("one", invokeStatic(cls, "run", 1));
        assertEquals("two", invokeStatic(cls, "run", 2));
        assertEquals("other", invokeStatic(cls, "run", 99));
    }

    @Test
    void switchWithFallthrough() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int run(int x) {\n" +
                        "        int r = 0;\n" +
                        "        switch (x) {\n" +
                        "            case 1: r += 1;\n" +
                        "            case 2: r += 2;\n" +
                        "            case 3: r += 3; break;\n" +
                        "            default: r = -1;\n" +
                        "        }\n" +
                        "        return r;\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals(6, invokeStatic(cls, "run", 1));
        assertEquals(5, invokeStatic(cls, "run", 2));
        assertEquals(3, invokeStatic(cls, "run", 3));
        assertEquals(-1, invokeStatic(cls, "run", 99));
    }

    @Test
    void labeledBreak() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int run() {\n" +
                        "        int sum = 0;\n" +
                        "        outer: for (int i = 0; i < 10; i++) {\n" +
                        "            for (int j = 0; j < 10; j++) {\n" +
                        "                if (i == 3 && j == 4) break outer;\n" +
                        "                sum++;\n" +
                        "            }\n" +
                        "        }\n" +
                        "        return sum;\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(34, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void nestedTryCatch() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run(int x) {\n" +
                        "        try {\n" +
                        "            try { if (x == 1) throw new RuntimeException(\"a\"); return \"ok\"; }\n" +
                        "            catch (RuntimeException e) {\n" +
                        "                if (x == 2) throw new IllegalStateException(\"b\");\n" +
                        "                return \"caught:\" + e.getMessage();\n" +
                        "            }\n" +
                        "        } catch (IllegalStateException e) { return \"outer:\" + e.getMessage(); }\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals("ok", invokeStatic(cls, "run", 0));
        assertEquals("caught:a", invokeStatic(cls, "run", 1));
    }
}
