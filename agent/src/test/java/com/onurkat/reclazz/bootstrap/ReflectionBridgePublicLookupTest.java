/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bridge used to cover only the {@code getDeclared*} half of the
 * reflection API. Frameworks reach for both halves, so a method added by a
 * structural reload was visible to code calling
 * {@code getDeclaredMethods()} and invisible to code calling
 * {@code getMethods()}, with nothing to tell you which one you were about
 * to depend on.
 *
 * These cover the public half: what it merges, what it must not merge, and
 * that it still behaves like the method it stands in for when there is
 * nothing to add.
 */
class ReflectionBridgePublicLookupTest {

    /** Stands in for a reloaded class; the "added" members are forged onto it. */
    public static class Host {
        public String original() { return "original"; }
        public String publicAdded() { return "should not be reached directly"; }
        String packagePrivateAdded() { return "not public"; }
        public String publicField = "";
        String packagePrivateField = "";
    }

    private static final Class<?> KEY = Host.class;

    @AfterEach
    void clearState() {
        ReflectionBridge.replaceClassState(KEY, List.of(), List.of());
    }

    private static Method hostMethod(String name) throws Exception {
        return Host.class.getDeclaredMethod(name);
    }

    private static Field hostField(String name) throws Exception {
        return Host.class.getDeclaredField(name);
    }

    @Test
    void getMethodsMergesAnAddedPublicMethod() throws Exception {
        Method added = hostMethod("publicAdded");
        ReflectionBridge.replaceClassState(KEY, List.of(added), List.of());

        List<String> names = Arrays.stream(ReflectionBridge.getMethods(Host.class))
                .map(Method::getName).toList();

        assertTrue(names.contains("publicAdded"), "the added method should be merged in");
        assertTrue(names.contains("original"), "the original methods must still be there");
        assertTrue(names.contains("equals"), "inherited public methods must still be there");
    }

    /**
     * {@code Class.getMethods()} returns public members only. Merging a
     * non-public one would hand callers something that cannot exist, and
     * anything filtering on modifiers afterwards would quietly disagree with
     * what it was given.
     */
    @Test
    void getMethodsDoesNotMergeANonPublicMethod() throws Exception {
        ReflectionBridge.replaceClassState(KEY, List.of(hostMethod("packagePrivateAdded")), List.of());

        List<String> names = Arrays.stream(ReflectionBridge.getMethods(Host.class))
                .map(Method::getName).toList();

        assertFalse(names.contains("packagePrivateAdded"),
                "getMethods() promises public members only");
    }

    @Test
    void getMethodFindsAnAddedPublicMethodAndStillWalksTheHierarchy() throws Exception {
        Method added = hostMethod("publicAdded");
        ReflectionBridge.replaceClassState(KEY, List.of(added), List.of());

        assertEquals(added, ReflectionBridge.getMethod(Host.class, "publicAdded"));
        assertNotNull(ReflectionBridge.getMethod(Host.class, "toString"),
                "inherited methods must still resolve through the fallback");
    }

    @Test
    void getMethodStillThrowsForSomethingThatIsNotThere() {
        assertThrows(NoSuchMethodException.class,
                () -> ReflectionBridge.getMethod(Host.class, "neverExisted"));
    }

    @Test
    void getFieldsMergesOnlyPublicAddedFields() throws Exception {
        ReflectionBridge.replaceClassState(KEY, List.of(),
                List.of(hostField("publicField"), hostField("packagePrivateField")));

        List<String> names = Arrays.stream(ReflectionBridge.getFields(Host.class))
                .map(Field::getName).toList();

        assertTrue(names.contains("publicField"));
        assertFalse(names.contains("packagePrivateField"),
                "getFields() promises public members only");
    }

    @Test
    void getFieldFindsAnAddedPublicField() throws Exception {
        Field added = hostField("publicField");
        ReflectionBridge.replaceClassState(KEY, List.of(), List.of(added));

        assertEquals(added, ReflectionBridge.getField(Host.class, "publicField"));
        assertThrows(NoSuchFieldException.class,
                () -> ReflectionBridge.getField(Host.class, "neverExisted"));
    }

    /**
     * The common case is a class with nothing added. The bridge sits on every
     * rewritten call site in the process, so when it has nothing to do it has
     * to return exactly what the JDK would have.
     */
    @Test
    void withNothingAddedTheAnswerIsTheJdkAnswer() throws Exception {
        assertArrayEquals(Host.class.getMethods(), ReflectionBridge.getMethods(Host.class));
        assertArrayEquals(Host.class.getFields(), ReflectionBridge.getFields(Host.class));
        assertEquals(Host.class.getMethod("original"),
                ReflectionBridge.getMethod(Host.class, "original"));
        assertEquals(Host.class.getField("publicField"),
                ReflectionBridge.getField(Host.class, "publicField"));
    }
}
