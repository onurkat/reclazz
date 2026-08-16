/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settles a question the product's own documentation answers with a guess.
 *
 * We tell users that changing an annotation needs enhanced redefinition,
 * meaning JetBrains Runtime or DCEVM. That claim came from watching a
 * {@code @RequestMapping} change fail to take effect end to end, which is a
 * behaviour, not a cause. The JVMTI specification says a redefinition "may
 * change method bodies, the constant pool and attributes", and annotations
 * are attributes, so a stock JVM ought to accept the change.
 *
 * If it does, the end-to-end failure is a stale framework cache and is ours
 * to fix. If it does not, the limitation is real and stays documented. This
 * test asks the JVM directly, with no framework in the way.
 */
class AnnotationRedefineProbeTest extends TransformTestBase {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setup() {
        instrumentation = ByteBuddyAgent.install();
        assertNotNull(instrumentation);
    }

    private static final String ROUTE_SRC =
            "import java.lang.annotation.*;\n" +
            "@Retention(RetentionPolicy.RUNTIME)\n" +
            "@Target(ElementType.METHOD)\n" +
            "public @interface Route { String value(); }";

    private static final String V1 =
            "public class RoutedClass {\n" +
            "    @Route(\"/ping\")\n" +
            "    public String handle() { return \"v1\"; }\n" +
            "}";

    private static final String V2 =
            "public class RoutedClass {\n" +
            "    @Route(\"/pong\")\n" +
            "    public String handle() { return \"v2\"; }\n" +
            "}";

    @Test
    void stockJvmAcceptsAnAnnotationValueChange() throws Exception {
        Class<?> clazz = defineAndLoad(
                compile(new SourceFile("Route", ROUTE_SRC),
                        new SourceFile("RoutedClass", V1)), "RoutedClass");

        assertEquals("/ping", routeValue(clazz.getDeclaredMethod("handle")),
                "precondition: the original annotation value should be readable");

        byte[] v2 = compile(new SourceFile("Route", ROUTE_SRC),
                            new SourceFile("RoutedClass", V2)).get("RoutedClass");
        instrumentation.redefineClasses(new ClassDefinition(clazz, v2));

        // The body change is the control: if this did not take, the
        // redefinition itself failed and the annotation result means nothing.
        Object instance = clazz.getDeclaredConstructor().newInstance();
        assertEquals("v2", clazz.getDeclaredMethod("handle").invoke(instance),
                "control: the method body change must have been applied");

        // A Method obtained after the redefinition, so no object-level cache
        // from before can explain the answer either way.
        String after = routeValue(clazz.getDeclaredMethod("handle"));

        System.out.println("PROBE annotation after redefinition = " + after);
        assertEquals("/pong", after,
                "the JVM did not surface the new annotation value; the limitation is real");
    }

    private static final String NONE =
            "public class RoutedClass {\n" +
            "    public String handle() { return \"v1\"; }\n" +
            "}";

    /**
     * The value change is the mild case. Adding an annotation that was not
     * there, and removing one that was, is what people actually do:
     * @Transactional on a method that lacked it, @Cacheable taken off one
     * that had it.
     */
    @Test
    void stockJvmAcceptsAddingAndRemovingAnAnnotation() throws Exception {
        Class<?> clazz = defineAndLoad(
                compile(new SourceFile("Route", ROUTE_SRC),
                        new SourceFile("RoutedClass", NONE)), "RoutedClass");

        assertNull(routeValue(clazz.getDeclaredMethod("handle")),
                "precondition: the method starts with no annotation");

        byte[] added = compile(new SourceFile("Route", ROUTE_SRC),
                               new SourceFile("RoutedClass", V1)).get("RoutedClass");
        instrumentation.redefineClasses(new ClassDefinition(clazz, added));
        String afterAdd = routeValue(clazz.getDeclaredMethod("handle"));
        System.out.println("PROBE after adding    = " + afterAdd);
        assertEquals("/ping", afterAdd, "adding an annotation was not surfaced");

        byte[] removed = compile(new SourceFile("Route", ROUTE_SRC),
                                 new SourceFile("RoutedClass", NONE)).get("RoutedClass");
        instrumentation.redefineClasses(new ClassDefinition(clazz, removed));
        String afterRemove = routeValue(clazz.getDeclaredMethod("handle"));
        System.out.println("PROBE after removing  = " + afterRemove);
        assertNull(afterRemove, "removing an annotation was not surfaced");
    }

    /** Reads @Route(value) without the annotation type being on our classpath. */
    private static String routeValue(Method m) throws Exception {
        for (java.lang.annotation.Annotation a : m.getAnnotations()) {
            if ("Route".equals(a.annotationType().getSimpleName())) {
                return (String) a.annotationType().getMethod("value").invoke(a);
            }
        }
        return null;
    }
}
