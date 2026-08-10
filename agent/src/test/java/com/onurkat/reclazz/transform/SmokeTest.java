package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest extends TransformTestBase {

    @Test
    void simpleStaticMethodRoundTrip() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Hello", "public class Hello { public static String greet() { return \"hi\"; } }")
        );
        Class<?> cls = defineAndLoad(classes, "Hello");
        assertEquals("hi", invokeStatic(cls, "greet"));
    }

    @Test
    void instanceMethodViaStaticEntry() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Wrap",
                        "public class Wrap {\n" +
                        "    private final String name;\n" +
                        "    public Wrap(String name) { this.name = name; }\n" +
                        "    public String greet() { return \"hi \" + name; }\n" +
                        "    public static String run() { return new Wrap(\"world\").greet(); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "Wrap");
        assertEquals("hi world", invokeStatic(cls, "run"));
    }
}
