/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enum and Record patterns from production code. */
class EnumAndRecordTest extends TransformTestBase {

    @Test
    void enumImplementingInterfaceWithFieldsAndMethods() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Computable",
                        "public interface Computable { int compute(int x); }"),
                new SourceFile("Op",
                        "public enum Op implements Computable {\n" +
                        "    ADD(1) { public int compute(int x) { return x + step; } },\n" +
                        "    MUL(2) { public int compute(int x) { return x * step; } },\n" +
                        "    SQUARE(0) { public int compute(int x) { return x * x; } };\n" +
                        "    final int step;\n" +
                        "    Op(int s) { this.step = s; }\n" +
                        "}"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static int run() {\n" +
                        "        return Op.ADD.compute(10) + Op.MUL.compute(3) + Op.SQUARE.compute(4);\n" +
                        "    }\n" +
                        "}")
        );
        // Op.ADD.compute(10) = 10 + 1 = 11
        // Op.MUL.compute(3) = 3 * 2 = 6
        // Op.SQUARE.compute(4) = 4 * 4 = 16
        // sum = 33
        assertEquals(33, invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void enumValuesAndValueOfPreserved() {
        // enum's synthetic values()/valueOf() must remain usable after transform.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Color",
                        "public enum Color { RED, GREEN, BLUE }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        StringBuilder sb = new StringBuilder();\n" +
                        "        for (Color c : Color.values()) sb.append(c.name()).append(\",\");\n" +
                        "        sb.append(\"|\").append(Color.valueOf(\"GREEN\").ordinal());\n" +
                        "        return sb.toString();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("RED,GREEN,BLUE,|1", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void recordWithMethodAndConstructorBody() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Money",
                        "public record Money(long amount, String currency) {\n" +
                        "    public Money {\n" +
                        "        if (amount < 0) throw new IllegalArgumentException(\"negative\");\n" +
                        "        if (currency == null) throw new NullPointerException(\"currency\");\n" +
                        "    }\n" +
                        "    public Money plus(long delta) { return new Money(amount + delta, currency); }\n" +
                        "    public String formatted() { return amount + \" \" + currency; }\n" +
                        "    public static String run() {\n" +
                        "        Money m = new Money(100, \"USD\").plus(50);\n" +
                        "        return m.formatted();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("150 USD", invokeStatic(defineAndLoad(classes, "Money"), "run"));
    }

    @Test
    void recordWithAnnotatedComponents() throws Exception {
        // Annotations on record components flow to multiple targets:
        // the implicit field, the accessor method, and the constructor parameter.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("JsonProperty",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})\n" +
                        "public @interface JsonProperty { String value(); }"),
                new SourceFile("UserDto",
                        "public record UserDto(\n" +
                        "    @JsonProperty(\"user_id\") String id,\n" +
                        "    @JsonProperty(\"display_name\") String name\n" +
                        ") {}")
        );
        Class<?> cls = defineAndLoad(classes, "UserDto");
        // Verify the record component carries the annotation
        RecordComponent[] components = cls.getRecordComponents();
        assertNotNull(components, "record components should be present");
        assertEquals(2, components.length);
        Annotation[] idAnnos = components[0].getAnnotations();
        assertEquals(1, idAnnos.length, "@JsonProperty should be on id component");
        // Verify accessor method also carries the annotation
        Method idAccessor = cls.getDeclaredMethod("id");
        assertEquals(1, idAccessor.getAnnotations().length, "accessor method should have @JsonProperty");
    }

    @Test
    void localRecordInsideMethod() {
        // Java 16+ allows declaring a record inside a method body.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        record Pair(int a, int b) {\n" +
                        "            int sum() { return a + b; }\n" +
                        "        }\n" +
                        "        Pair p = new Pair(3, 4);\n" +
                        "        return \"sum=\" + p.sum();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("sum=7", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }

    @Test
    void enumWithStaticFactory() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Status",
                        "public enum Status {\n" +
                        "    ACTIVE(\"a\"), INACTIVE(\"i\"), PENDING(\"p\");\n" +
                        "    private final String code;\n" +
                        "    Status(String code) { this.code = code; }\n" +
                        "    public String getCode() { return code; }\n" +
                        "    public static Status fromCode(String c) {\n" +
                        "        for (Status s : values()) if (s.code.equals(c)) return s;\n" +
                        "        throw new IllegalArgumentException(c);\n" +
                        "    }\n" +
                        "}"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    public static String run() {\n" +
                        "        return Status.fromCode(\"p\").name();\n" +
                        "    }\n" +
                        "}")
        );
        assertEquals("PENDING", invokeStatic(defineAndLoad(classes, "T"), "run"));
    }
}
