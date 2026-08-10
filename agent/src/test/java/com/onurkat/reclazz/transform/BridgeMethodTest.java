package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Generic type erasure produces synthetic bridge methods. */
class BridgeMethodTest extends TransformTestBase {

    @Test
    void genericSubclassBridgeMethod() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Container",
                        "public class Container<T> {\n" +
                        "    public T get(T input) { return input; }\n" +
                        "}"),
                new SourceFile("StringContainer",
                        "public class StringContainer extends Container<String> {\n" +
                        "    @Override\n" +
                        "    public String get(String input) { return \"wrapped:\" + input; }\n" +
                        "    public static String run() { return new StringContainer().get(\"hi\"); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "StringContainer");
        assertEquals("wrapped:hi", invokeStatic(cls, "run"));
    }

    @Test
    void invokeBridgeMethodViaParentInterface() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Container",
                        "public class Container<T> {\n" +
                        "    public T identity(T x) { return x; }\n" +
                        "}"),
                new SourceFile("IntContainer",
                        "public class IntContainer extends Container<Integer> {\n" +
                        "    @Override\n" +
                        "    public Integer identity(Integer x) { return x * 2; }\n" +
                        "    public static int run() {\n" +
                        "        Container<Integer> c = new IntContainer();\n" +
                        "        return c.identity(21);\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "IntContainer");
        assertEquals(42, invokeStatic(cls, "run"));
    }

    @Test
    void functionInterfaceWithBridge() {
        // Function<Integer, Integer>.apply has bridge method
        // (Object)Object that delegates to the typed (Integer)Integer
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("DoubleIt",
                        "import java.util.function.*;\n" +
                        "public class DoubleIt implements Function<Integer, Integer> {\n" +
                        "    @Override\n" +
                        "    public Integer apply(Integer x) { return x * 2; }\n" +
                        "    public static int run() {\n" +
                        "        Function<Integer, Integer> f = new DoubleIt();\n" +
                        "        return f.apply(11);\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "DoubleIt");
        assertEquals(22, invokeStatic(cls, "run"));
    }
}
