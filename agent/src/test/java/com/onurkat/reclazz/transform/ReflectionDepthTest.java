package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflection metadata that frameworks read from trampoline methods beyond
 * the basic getParameters() / getAnnotations() paths covered in
 * {@link MetadataPreservationTest}.
 */
class ReflectionDepthTest extends TransformTestBase {

    @Test
    void getDeclaredAnnotations() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("MyAnno",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "public @interface MyAnno {}"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @MyAnno public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run");
        assertEquals(1, m.getDeclaredAnnotations().length);
    }

    @Test
    void repeatableAnnotations() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Tag",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "@Repeatable(Tags.class)\n" +
                        "public @interface Tag { String value(); }"),
                new SourceFile("Tags",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.METHOD)\n" +
                        "public @interface Tags { Tag[] value(); }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @Tag(\"a\") @Tag(\"b\") @Tag(\"c\")\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run");
        Class<?> tagClass = cls.getClassLoader().loadClass("Tag");
        @SuppressWarnings("unchecked")
        Annotation[] tags = m.getAnnotationsByType((Class<? extends Annotation>) tagClass);
        assertEquals(3, tags.length, "Three @Tag annotations should be present");
    }

    @Test
    void genericReturnTypePreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public List<String> list() { return Arrays.asList(\"a\"); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("list");
        Type genericType = m.getGenericReturnType();
        assertTrue(genericType instanceof ParameterizedType, "should be parameterized");
        ParameterizedType pt = (ParameterizedType) genericType;
        assertEquals(List.class, pt.getRawType());
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
    }

    @Test
    void genericParameterTypesPreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public void take(Map<String, Integer> m) {}\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("take", Map.class);
        Type[] types = m.getGenericParameterTypes();
        assertEquals(1, types.length);
        ParameterizedType pt = (ParameterizedType) types[0];
        assertEquals(Map.class, pt.getRawType());
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
        assertEquals(Integer.class, pt.getActualTypeArguments()[1]);
    }

    @Test
    void exceptionTypesPreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.io.*;\n" +
                        "import java.sql.*;\n" +
                        "public class T {\n" +
                        "    public void run() throws IOException, SQLException {}\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run");
        Class<?>[] exceptions = m.getExceptionTypes();
        assertEquals(2, exceptions.length);
        assertEquals(IOException.class, exceptions[0]);
        assertEquals(SQLException.class, exceptions[1]);
    }

    @Test
    void varargsFlagPreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public int sum(int... xs) { int s = 0; for (int x : xs) s += x; return s; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("sum", int[].class);
        assertTrue(m.isVarArgs(), "trampoline should preserve ACC_VARARGS");
    }

    @Test
    void toGenericString() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "import java.util.*;\n" +
                        "public class T {\n" +
                        "    public List<String> handle(Map<Integer, String> in) { return null; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("handle", Map.class);
        String s = m.toGenericString();
        assertTrue(s.contains("List<java.lang.String>"), "expected List<String> in: " + s);
        assertTrue(s.contains("Map<java.lang.Integer, java.lang.String>"),
                "expected Map<Integer, String> in: " + s);
    }
}
