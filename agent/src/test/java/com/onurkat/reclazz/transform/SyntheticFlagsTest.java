package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring (and most frameworks) filter out synthetic methods/fields when
 * scanning classes (e.g. {@code BeanUtils.findMethod}, classpath scanners).
 * The trampoline must NOT be synthetic — it's the public API the framework
 * sees. The renamed copy and the infrastructure fields MUST be synthetic so
 * frameworks ignore them.
 */
class SyntheticFlagsTest extends TransformTestBase {

    @Test
    void trampolineIsNotSynthetic() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method m = cls.getDeclaredMethod("run");
        assertFalse(m.isSynthetic(),
                "Trampoline must NOT be synthetic — Spring filters synthetic methods out");
    }

    @Test
    void renamedCopyIsSynthetic() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Method renamed = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().startsWith("__reclazz$v0$run$")) {
                renamed = m;
                break;
            }
        }
        assertNotNull(renamed, "Renamed copy should exist");
        assertTrue(renamed.isSynthetic(),
                "Renamed __reclazz$v0$ method MUST be synthetic so frameworks skip it");
    }

    @Test
    void infrastructureFieldsAreSynthetic() {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        Field ext = null;
        Field lookup = null;
        for (Field f : cls.getDeclaredFields()) {
            if ("__reclazz$ext".equals(f.getName())) ext = f;
            if ("__reclazz$lookup".equals(f.getName())) lookup = f;
        }
        assertNotNull(ext, "__reclazz$ext field should exist");
        assertNotNull(lookup, "__reclazz$lookup field should exist");
        assertTrue(ext.isSynthetic(), "__reclazz$ext must be synthetic");
        assertTrue(lookup.isSynthetic(), "__reclazz$lookup must be synthetic");
    }

    @Test
    void springLikeMethodFilteringSeesOnlyOriginal() {
        // Emulate Spring's BeanUtils-style scan: skip synthetic methods,
        // only public non-bridge non-synthetic method named "run" should be returned.
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("T",
                        "public class T {\n" +
                        "    public String run() { return \"ok\"; }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "T");
        int candidates = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals("run") && !m.isSynthetic() && !m.isBridge()) {
                candidates++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, candidates,
                "Exactly one non-synthetic 'run' method should be visible to frameworks");
    }
}
