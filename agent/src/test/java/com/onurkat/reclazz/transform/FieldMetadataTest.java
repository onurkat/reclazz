package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Field-level reflection metadata (Spring DI uses @Autowired on fields). */
class FieldMetadataTest extends TransformTestBase {

    @Test
    void fieldAnnotationsPreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Inject",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.FIELD)\n" +
                        "public @interface Inject { String name() default \"\"; }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @Inject(name = \"svc\") private String service;\n" +
                        "    public String getService() { return service; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Field f = cls.getDeclaredField("service");
        Annotation[] annos = f.getAnnotations();
        assertEquals(1, annos.length, "Field should have @Inject annotation");
        Class<?> annoType = annos[0].annotationType();
        assertEquals("svc", annoType.getMethod("name").invoke(annos[0]));
    }

    @Test
    void multipleFieldAnnotationsPreserved() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("A1",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.FIELD)\n" +
                        "public @interface A1 {}"),
                new SourceFile("A2",
                        "import java.lang.annotation.*;\n" +
                        "@Retention(RetentionPolicy.RUNTIME)\n" +
                        "@Target(ElementType.FIELD)\n" +
                        "public @interface A2 { int value(); }"),
                new SourceFile("T",
                        "public class T {\n" +
                        "    @A1 @A2(7) private String f;\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Field f = cls.getDeclaredField("f");
        assertEquals(2, f.getDeclaredAnnotations().length);
    }

    @Test
    void infrastructureFieldsCanBeFiltered() {
        // A Spring-like scanner that ignores synthetic fields should see
        // only the user-declared field.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    private String name = \"hi\";\n" +
                        "    public String getName() { return name; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        int userFields = 0;
        for (Field f : cls.getDeclaredFields()) {
            if (!f.isSynthetic()) userFields++;
        }
        assertEquals(1, userFields, "exactly one user field; __reclazz$ext / $lookup should be synthetic");
    }
}
