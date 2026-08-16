/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lower-priority edge cases that are still worth covering: volatile fields,
 * deeply nested classes, real CGLIB-style subclass proxies, large method
 * bodies pushing toward bytecode limits.
 */
class AdvancedEdgeCasesTest extends TransformTestBase {

    @Test
    void volatileFieldWriteAndRead() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Counter",
                        "public class Counter {\n" +
                        "    private volatile int count;\n" +
                        "    public void inc() { count++; }\n" +
                        "    public int get() { return count; }\n" +
                        "    public static int run() {\n" +
                        "        Counter c = new Counter();\n" +
                        "        for (int i = 0; i < 5; i++) c.inc();\n" +
                        "        return c.get();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(5, invokeStatic(defineAndLoad(classes, "Counter"), "run"));
    }

    @Test
    void volatileFinalCombination() {
        // volatile + final on the same field is illegal in Java, but volatile
        // alone is common. Verify our transformer doesn't break GETFIELD/
        // PUTFIELD on volatile fields.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Holder",
                        "public class Holder {\n" +
                        "    private volatile String value = \"initial\";\n" +
                        "    public void set(String v) { this.value = v; }\n" +
                        "    public String get() { return value; }\n" +
                        "    public static String run() {\n" +
                        "        Holder h = new Holder();\n" +
                        "        h.set(\"updated\");\n" +
                        "        return h.get();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("updated", invokeStatic(defineAndLoad(classes, "Holder"), "run"));
    }

    @Test
    void deeplyNestedNonAnonymousInnerClasses() {
        // L1 -> L2 -> L3 -> L4 inner classes accessing outer's private field.
        // Each level needs synthetic accessor chains.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("L1",
                        "public class L1 {\n" +
                        "    private int base = 100;\n" +
                        "    public class L2 {\n" +
                        "        private int level2 = 20;\n" +
                        "        public class L3 {\n" +
                        "            private int level3 = 3;\n" +
                        "            public class L4 {\n" +
                        "                public int compute() { return base + level2 + level3; }\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "    public static int run() {\n" +
                        "        L1 l1 = new L1();\n" +
                        "        L2 l2 = l1.new L2();\n" +
                        "        L2.L3 l3 = l2.new L3();\n" +
                        "        L2.L3.L4 l4 = l3.new L4();\n" +
                        "        return l4.compute();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(123, invokeStatic(defineAndLoad(classes, "L1"), "run"));
    }

    @Test
    void aspectStyleClassWithAroundAdvice() {
        // Simulates an @Aspect class — a class with methods that wrap a
        // Runnable / Callable, no actual AOP runtime.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("LoggingAspect",
                        "import java.util.concurrent.*;\n" +
                        "public class LoggingAspect {\n" +
                        "    public static <T> T around(String name, Callable<T> target) throws Exception {\n" +
                        "        long start = System.nanoTime();\n" +
                        "        try {\n" +
                        "            return target.call();\n" +
                        "        } finally {\n" +
                        "            long elapsed = System.nanoTime() - start;\n" +
                        "            // \"log\" — not really\n" +
                        "        }\n" +
                        "    }\n" +
                        "    public static String run() throws Exception {\n" +
                        "        return around(\"work\", () -> \"done\");\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("done", invokeStatic(defineAndLoad(classes, "LoggingAspect"), "run"));
    }

    @Test
    void byteBuddyCglibStyleSubclassProxy() throws Exception {
        // Real CGLIB-style subclass proxy via ByteBuddy: subclass our
        // trampolined class, intercept all methods, delegate to super
        // (which goes through our trampoline + INVOKESPECIAL fix from 1.0.11).
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Service",
                        "public class Service {\n" +
                        "    public String process(String input) { return \"raw:\" + input; }\n" +
                        "    public int doubleIt(int x) { return x * 2; }\n" +
                        "}")
        );
        Class<?> serviceCls = defineAndLoad(classes, "Service");

        // ByteBuddy subclass that intercepts every method and prepends
        // "[proxied]" then calls super
        Class<?> proxyCls = new ByteBuddy()
                .subclass(serviceCls)
                .method(net.bytebuddy.matcher.ElementMatchers.named("process"))
                .intercept(MethodDelegation.to(ProcessInterceptor.class))
                .make()
                .load(serviceCls.getClassLoader(),
                        net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        Object instance = proxyCls.getDeclaredConstructor().newInstance();
        Object result = serviceCls.getMethod("process", String.class).invoke(instance, "hello");
        assertEquals("[proxied] raw:hello", result);

        // Non-intercepted method still calls super directly
        Object num = serviceCls.getMethod("doubleIt", int.class).invoke(instance, 21);
        assertEquals(42, num);
    }

    public static class ProcessInterceptor {
        @RuntimeType
        public static Object intercept(@SuperCall Callable<?> zuper) throws Exception {
            return "[proxied] " + zuper.call();
        }
    }

    @Test
    void largeMethodBodyApproachingLimits() {
        // Build a method with ~3000 bytecode instructions and ~100 locals
        // to stress COMPUTE_FRAMES and the stackmap generation.
        StringBuilder body = new StringBuilder();
        body.append("int sum = 0;\n");
        for (int i = 0; i < 200; i++) {
            body.append("int v").append(i).append(" = ").append(i * 3).append(";\n");
            body.append("sum += v").append(i).append(" * 2;\n");
        }
        body.append("return sum;\n");
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Big",
                        "public class Big {\n" +
                        "    public static int run() {\n" + body + "    }\n" +
                        "}")
        );
        // sum = 2 * sum(i*3 for i in 0..199) = 6 * (0+1+...+199) = 6 * 19900 = 119400
        assertEquals(119400, invokeStatic(defineAndLoad(classes, "Big"), "run"));
    }

    @Test
    void manyExceptionHandlersInOneMethod() {
        // 20 try-catch blocks in one method — stresses exception table
        StringBuilder body = new StringBuilder();
        body.append("StringBuilder sb = new StringBuilder();\n");
        for (int i = 0; i < 20; i++) {
            body.append("try { if (i == ").append(i).append(") throw new RuntimeException(\"")
                .append(i).append("\"); } catch (RuntimeException e) { sb.append(e.getMessage()).append(\",\"); }\n");
        }
        body.append("return sb.toString();\n");
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("ExceptionHeavy",
                        "public class ExceptionHeavy {\n" +
                        "    public static String run(int i) {\n" + body + "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "ExceptionHeavy");
        assertEquals("0,", invokeStatic(cls, "run", 0));
        assertEquals("5,", invokeStatic(cls, "run", 5));
        assertEquals("19,", invokeStatic(cls, "run", 19));
        assertEquals("", invokeStatic(cls, "run", 99));
    }

    @Test
    void enumSetUsage() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Day",
                        "public enum Day { MON, TUE, WED, THU, FRI, SAT, SUN }"),
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public static int run() {\n" +
                        "        EnumSet<Day> weekdays = EnumSet.range(Day.MON, Day.FRI);\n" +
                        "        EnumSet<Day> weekend = EnumSet.complementOf(weekdays);\n" +
                        "        return weekdays.size() * 10 + weekend.size();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals(52, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void staticMethodReturningSelfFromBuilder() {
        // Fluent API where every method returns this — common builder
        // shape that exercises ARETURN with `this` on the stack.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("StringBuilderLike",
                        "public class StringBuilderLike {\n" +
                        "    private final StringBuilder sb = new StringBuilder();\n" +
                        "    public StringBuilderLike a(String s) { sb.append(s); return this; }\n" +
                        "    public StringBuilderLike b(int n) { sb.append(n); return this; }\n" +
                        "    public StringBuilderLike c(boolean v) { sb.append(v); return this; }\n" +
                        "    public String build() { return sb.toString(); }\n" +
                        "    public static String run() {\n" +
                        "        return new StringBuilderLike().a(\"hi-\").b(42).a(\"-\").c(true).build();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("hi-42-true", invokeStatic(defineAndLoad(classes, "StringBuilderLike"), "run"));
    }
}
