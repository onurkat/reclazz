package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Parent/child class chains where both are watched. */
class InheritanceTest extends TransformTestBase {

    @Test
    void simpleOverride() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Parent",
                        "public class Parent {\n" +
                        "    public String greet() { return \"parent\"; }\n" +
                        "}"),
                new SourceFile("Child",
                        "public class Child extends Parent {\n" +
                        "    @Override public String greet() { return \"child\"; }\n" +
                        "    public static String run() { return new Child().greet(); }\n" +
                        "}")
        );
        assertEquals("child", invokeStatic(defineAndLoad(classes, "Child"), "run"));
    }

    @Test
    void superCallFromOverride() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Parent",
                        "public class Parent {\n" +
                        "    public String greet() { return \"hi\"; }\n" +
                        "}"),
                new SourceFile("Child",
                        "public class Child extends Parent {\n" +
                        "    @Override public String greet() { return super.greet() + \"!\"; }\n" +
                        "    public static String run() { return new Child().greet(); }\n" +
                        "}")
        );
        assertEquals("hi!", invokeStatic(defineAndLoad(classes, "Child"), "run"));
    }

    @Test
    void parentMethodCalledViaParentReference() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Parent",
                        "public class Parent {\n" +
                        "    public String greet() { return \"parent\"; }\n" +
                        "}"),
                new SourceFile("Child",
                        "public class Child extends Parent {}"),
                new SourceFile("Caller",
                        "public class Caller {\n" +
                        "    public static String run() {\n" +
                        "        Parent p = new Child();\n" +
                        "        return p.greet();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("parent", invokeStatic(defineAndLoad(classes, "Caller"), "run"));
    }

    @Test
    void protectedFieldFromChild() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Parent",
                        "public class Parent {\n" +
                        "    protected int value;\n" +
                        "    public Parent(int v) { this.value = v; }\n" +
                        "}"),
                new SourceFile("Child",
                        "public class Child extends Parent {\n" +
                        "    public Child() { super(7); }\n" +
                        "    public int doubled() { return value * 2; }\n" +
                        "    public static int run() { return new Child().doubled(); }\n" +
                        "}")
        );
        assertEquals(14, invokeStatic(defineAndLoad(classes, "Child"), "run"));
    }

    @Test
    void abstractParentConcreteChild() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Parent",
                        "public abstract class Parent {\n" +
                        "    public abstract int compute();\n" +
                        "    public int doubled() { return compute() * 2; }\n" +
                        "}"),
                new SourceFile("Child",
                        "public class Child extends Parent {\n" +
                        "    public int compute() { return 21; }\n" +
                        "    public static int run() { return new Child().doubled(); }\n" +
                        "}")
        );
        assertEquals(42, invokeStatic(defineAndLoad(classes, "Child"), "run"));
    }
}
