/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflection-based metadata that frameworks (Spring, Hybris, JPA) read from
 * trampoline methods. The trampoline replaces the original method, so its
 * parameter names and annotations must match the original — otherwise
 * frameworks like Spring's @RequestParam without explicit name will fail with
 * "Ensure that the compiler uses the '-parameters' flag".
 */
class MetadataPreservationTest extends TransformTestBase {

    @Test
    void parameterNamesPreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Ctrl",
                        "public class Ctrl {\n" +
                        "    public String handle(String username, int age) { return username + age; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "Ctrl");
        Method method = cls.getDeclaredMethod("handle", String.class, int.class);
        Parameter[] params = method.getParameters();
        assertEquals(2, params.length);
        assertTrue(params[0].isNamePresent(),
                "Parameter 0 name should be present (compiled with -parameters)");
        assertEquals("username", params[0].getName());
        assertEquals("age", params[1].getName());
    }

    @Test
    void methodAnnotationsPreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("MyAnno",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "public @interface MyAnno { String value() default \"\"; int n() default 0; }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @MyAnno(value = \"hello\", n = 42)\n" +
                        "    public String handle() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method method = cls.getDeclaredMethod("handle");
        Annotation[] annos = method.getAnnotations();
        assertEquals(1, annos.length, "Method should have @MyAnno on the trampoline");
        Annotation myAnno = annos[0];
        Class<?> annoType = myAnno.annotationType();
        assertEquals("MyAnno", annoType.getSimpleName());
        assertEquals("hello", annoType.getMethod("value").invoke(myAnno));
        assertEquals(42, annoType.getMethod("n").invoke(myAnno));
    }

    @Test
    void parameterAnnotationsPreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("MyParam",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.PARAMETER)\n" +
                        "public @interface MyParam { String value(); }"),
                new SourceFile("Ctrl",
                        "public class Ctrl {\n" +
                        "    public String handle(@MyParam(\"id\") String id, @MyParam(\"name\") String name) {\n" +
                        "        return id + name;\n" +
                        "    }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "Ctrl");
        Method method = cls.getDeclaredMethod("handle", String.class, String.class);
        Annotation[][] paramAnnos = method.getParameterAnnotations();
        assertEquals(2, paramAnnos.length);
        assertEquals(1, paramAnnos[0].length, "Param 0 should have @MyParam");
        assertEquals(1, paramAnnos[1].length, "Param 1 should have @MyParam");
        Annotation a0 = paramAnnos[0][0];
        Annotation a1 = paramAnnos[1][0];
        assertEquals("id", a0.annotationType().getMethod("value").invoke(a0));
        assertEquals("name", a1.annotationType().getMethod("value").invoke(a1));
    }
}
