package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSR 308 TYPE_USE annotations (e.g. @NonNull, @Tainted). */
class TypeAnnotationTest extends TransformTestBase {

    @Test
    void typeUseAnnotationOnReturn() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("NonNull",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.TYPE_USE)\n" +
                        "public @interface NonNull {}"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    public @NonNull String greet() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("greet");
        AnnotatedType at = m.getAnnotatedReturnType();
        Annotation[] annos = at.getAnnotations();
        assertEquals(1, annos.length, "TYPE_USE @NonNull on return type should be preserved");
        assertEquals("NonNull", annos[0].annotationType().getSimpleName());
    }

    @Test
    void typeUseAnnotationOnParameter() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Tainted",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.TYPE_USE)\n" +
                        "public @interface Tainted {}"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    public void run(@Tainted String input) {}\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run", String.class);
        AnnotatedType[] params = m.getAnnotatedParameterTypes();
        assertEquals(1, params.length);
        Annotation[] annos = params[0].getAnnotations();
        assertEquals(1, annos.length, "TYPE_USE @Tainted on parameter type should be preserved");
    }
}
