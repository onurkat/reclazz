/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a framework sees when it looks at a watched class.
 *
 * <p>The transform moves every method body to a renamed copy and leaves a
 * method under the original name that dispatches to it. Frameworks do not call
 * methods, they interrogate them first: Spring reads the annotations to decide
 * whether to proxy, Jackson reads the generic return type to decide what to
 * build, validation reads the parameter annotations. Every one of those answers
 * comes from the method under the original name, which is now a method the
 * developer did not write.
 *
 * <p>They all survive, and this says so by measurement rather than by reading
 * the adapter: annotations, the generic signature, the throws clause, the type
 * parameters. The E2E suite proves it end to end for the few that Spring MVC
 * happens to use; this proves it for the shape.
 */
class ReflectiveSurfaceTest extends TransformTestBase {

    private static Class<?> transform(String name, String source) throws Exception {
        byte[] raw = compile(new SourceFile(name, source)).get(name);
        TransformContext context = new TransformContext();
        context.addWatched(name);
        ReclazzTransformer transformer = new ReclazzTransformer(context, AgentConfig.parse(null));
        byte[] transformed = transformer.transform(
                TransformTestBase.class.getClassLoader(), name, null, null, raw);
        assertNotNull(transformed, "the class was watched, so it should have been transformed");
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put(name, transformed);
        return defineAndLoad(one, name);
    }

    @Test
    void theMethodUnderTheOriginalNameKeepsItsAnnotations() throws Exception {
        Class<?> cls = transform("Annotated",
                "public class Annotated {\n"
                        + "    @Deprecated\n"
                        + "    public String work(String in) { return in; }\n"
                        + "}");

        assertTrue(cls.getMethod("work", String.class).isAnnotationPresent(Deprecated.class),
                "Spring decides whether to proxy from this answer");
    }

    @Test
    void itKeepsTheGenericSignatureAndTheThrowsClause() throws Exception {
        Class<?> cls = transform("Generic",
                "import java.util.*;\n"
                        + "public class Generic {\n"
                        + "    public List<String> names(Map<String, Integer> in)\n"
                        + "            throws java.io.IOException {\n"
                        + "        return new ArrayList<>(in.keySet());\n"
                        + "    }\n"
                        + "    public <T extends Number> T pick(List<T> in) { return in.get(0); }\n"
                        + "}");

        Method names = cls.getMethod("names", Map.class);
        assertEquals("java.util.List<java.lang.String>",
                names.getGenericReturnType().getTypeName(),
                "erased here, Jackson builds a List of LinkedHashMap instead of the element type");
        assertEquals("java.util.Map<java.lang.String, java.lang.Integer>",
                names.getGenericParameterTypes()[0].getTypeName());
        assertArrayEquals(new Class<?>[]{java.io.IOException.class}, names.getExceptionTypes());

        Method pick = cls.getMethod("pick", List.class);
        assertEquals(1, pick.getTypeParameters().length);
        assertEquals("T", pick.getGenericReturnType().getTypeName());
    }

    /**
     * Frameworks enumerate declared members and skip the synthetic ones, which
     * is how Spring's own {@code ReflectionUtils} filter is written. So being
     * synthetic is what keeps the injected members out of a scan that would
     * otherwise find two methods carrying the same annotation.
     */
    @Test
    void everythingInjectedIsSyntheticSoScansSkipIt() throws Exception {
        Class<?> cls = transform("Scanned",
                "public class Scanned {\n"
                        + "    public String kept = \"k\";\n"
                        + "    public String work() { return \"w\"; }\n"
                        + "    public String plain() { return \"p\"; }\n"
                        + "}");

        List<String> methods = new java.util.ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.isSynthetic()) methods.add(m.getName());
        }
        java.util.Collections.sort(methods);
        assertEquals(List.of("plain", "work"), methods,
                "a scan that skips synthetic members should see the developer's own methods only");

        List<String> fields = new java.util.ArrayList<>();
        for (Field f : cls.getDeclaredFields()) {
            if (!f.isSynthetic()) fields.add(f.getName());
        }
        assertEquals(List.of("kept"), fields);
    }

    /**
     * A watched frame costs a second frame with an internal name above it, and
     * that is not fixable without giving up the dispatch the whole design rests
     * on. What has to hold is that the readable frame is still there, with the
     * developer's own method name and the line the failure is on, so the trace
     * still says where to look and an IDE can still jump from it. It did not:
     * the trampoline runs no statements of its own, so it was given no line
     * table and the frame read "Unknown Source".
     */
    @Test
    void aFailureStillNamesTheDevelopersOwnMethodAndLine() throws Exception {
        Class<?> cls = transform("Thrower",
                "public class Thrower {\n"
                        + "    public void boom() {\n"
                        + "        throw new IllegalStateException(\"x\");\n"
                        + "    }\n"
                        + "}");
        Object instance = cls.getDeclaredConstructor().newInstance();

        StackTraceElement readable = null;
        try {
            cls.getMethod("boom").invoke(instance);
            fail("boom should have thrown");
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            for (StackTraceElement frame : wrapped.getCause().getStackTrace()) {
                if ("Thrower".equals(frame.getClassName()) && "boom".equals(frame.getMethodName())) {
                    readable = frame;
                    break;
                }
            }
        }

        assertNotNull(readable, "the trace has to name boom, whatever else it also names");
        assertEquals("Thrower.java", readable.getFileName());
        assertEquals(3, readable.getLineNumber(), "the line the throw is actually on");
    }
}
