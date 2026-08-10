package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Java 14+ language features. */
class ModernJavaTest extends TransformTestBase {

    @Test
    void record() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Point",
                        "public record Point(int x, int y) {\n" +
                        "    public int sum() { return x + y; }\n" +
                        "    public static int run() { return new Point(3, 4).sum(); }\n" +
                        "}")
        );
        assertEquals(7, invokeStatic(defineAndLoad(classes, "Point"), "run"));
    }

    @Test
    void recordWithCanonicalConstructor() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Point",
                        "public record Point(int x, int y) {\n" +
                        "    public Point {\n" +
                        "        if (x < 0) throw new IllegalArgumentException(\"negative x\");\n" +
                        "    }\n" +
                        "    public static int run() { return new Point(3, 4).x() + new Point(5, 6).y(); }\n" +
                        "}")
        );
        assertEquals(9, invokeStatic(defineAndLoad(classes, "Point"), "run"));
    }

    @Test
    void patternInstanceof() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run(Object o) {\n" +
                        "        if (o instanceof String s) return \"str:\" + s.length();\n" +
                        "        if (o instanceof Integer i) return \"int:\" + (i * 2);\n" +
                        "        return \"other\";\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        assertEquals("str:5", invokeStatic(cls, "run", "hello"));
        assertEquals("int:14", invokeStatic(cls, "run", 7));
    }

    @Test
    void textBlock() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        return \"\"\"\n" +
                        "                hello\n" +
                        "                world\n" +
                        "                \"\"\";\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("hello\nworld\n", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void sealedHierarchy() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Shape",
                        "public sealed interface Shape permits Circle, Square {}"),
                new SourceFile("Circle",
                        "public final class Circle implements Shape { public double r; public Circle(double r) { this.r = r; } }"),
                new SourceFile("Square",
                        "public final class Square implements Shape { public double side; public Square(double s) { this.side = s; } }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static double area(Shape s) {\n" +
                        "        if (s instanceof Circle c) return c.r * c.r * 3.14;\n" +
                        "        if (s instanceof Square sq) return sq.side * sq.side;\n" +
                        "        return 0;\n" +
                        "    }\n" +
                        "    public static double run() { return area(new Square(4)); }\n" +
                        "}")
        );
        assertEquals(16.0, (double) invokeStatic(defineAndLoad(classes, "T"), "run"));
    }
}
