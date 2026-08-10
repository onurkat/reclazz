package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Annotation value types that flow through MultiplexAnnotationVisitor:
 * String[], Class<?>, enum, and nested annotations.
 */
class AnnotationValueTest extends TransformTestBase {

    @Test
    void annotationWithStringArrayValue() throws Exception {
        // Spring @Secured / Hybris role-style annotation
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Roles",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "public @interface Roles { String[] value(); }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @Roles({\"admin\", \"editor\", \"viewer\"})\n" +
                        "    public String secured() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("secured");
        Annotation[] annos = m.getAnnotations();
        assertEquals(1, annos.length);
        Object value = annos[0].annotationType().getMethod("value").invoke(annos[0]);
        assertArrayEquals(new String[]{"admin", "editor", "viewer"}, (String[]) value);
    }

    @Test
    void annotationWithClassValue() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Wired",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "public @interface Wired { Class<?> value(); }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @Wired(String.class)\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run");
        Annotation anno = m.getAnnotations()[0];
        Object value = anno.annotationType().getMethod("value").invoke(anno);
        assertEquals(String.class, value);
    }

    @Test
    void annotationWithEnumValue() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Level",
                        "public enum Level { LOW, MEDIUM, HIGH }"),
                new SourceFile("Priority",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "public @interface Priority { Level value(); }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @Priority(Level.HIGH)\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run");
        Annotation anno = m.getAnnotations()[0];
        Object value = anno.annotationType().getMethod("value").invoke(anno);
        assertEquals("HIGH", value.toString());
    }

    @Test
    void annotationWithNestedAnnotation() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Inner",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "public @interface Inner { String name(); int n(); }"),
                new SourceFile("Outer",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "public @interface Outer { Inner inner(); String label(); }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @Outer(label = \"outer\", inner = @Inner(name = \"x\", n = 42))\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run");
        Annotation outer = m.getAnnotations()[0];
        Class<?> outerType = outer.annotationType();
        assertEquals("outer", outerType.getMethod("label").invoke(outer));
        Object inner = outerType.getMethod("inner").invoke(outer);
        Class<?> innerType = inner.getClass();
        // inner is a proxy; the annotationType is the @interface
        Class<?> innerAnnoType = ((Annotation) inner).annotationType();
        assertEquals("x", innerAnnoType.getMethod("name").invoke(inner));
        assertEquals(42, innerAnnoType.getMethod("n").invoke(inner));
    }

    @Test
    void annotationWithMixedScalarAndArray() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Config",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "public @interface Config {\n" +
                        "    String name() default \"\";\n" +
                        "    int[] thresholds() default {};\n" +
                        "    Class<?>[] types() default {};\n" +
                        "    boolean enabled() default true;\n" +
                        "}"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @Config(name = \"cfg\", thresholds = {1, 2, 3}, types = {String.class, Integer.class}, enabled = false)\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run");
        Annotation anno = m.getAnnotations()[0];
        Class<?> at = anno.annotationType();
        assertEquals("cfg", at.getMethod("name").invoke(anno));
        assertArrayEquals(new int[]{1, 2, 3}, (int[]) at.getMethod("thresholds").invoke(anno));
        Class<?>[] types = (Class<?>[]) at.getMethod("types").invoke(anno);
        assertEquals(String.class, types[0]);
        assertEquals(Integer.class, types[1]);
        assertEquals(false, at.getMethod("enabled").invoke(anno));
    }
}
