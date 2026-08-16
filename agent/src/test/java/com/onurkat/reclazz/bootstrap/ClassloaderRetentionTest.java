/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An agent that outlives what it instruments must not hold on to it.
 *
 * The state here is per class, and the obvious way to hold it is a map keyed by
 * the class or its name. Both leak, and the second one is the trap: a
 * WeakHashMap looks like it solves this and does not, because the values are
 * call-site handles and forged Method objects, each of which references the
 * class that is the key. An entry that references its own key is an entry that
 * is never collected.
 *
 * Nothing shows in a reload loop, where the class stays loaded anyway. It shows
 * when a classloader is discarded: a redeployed web application, whose whole
 * loader and every class in it is then held by the agent for as long as the
 * server runs. That is the leak everyone blames an agent for, so it is worth a
 * test that fails if the holding ever comes back.
 */
class ClassloaderRetentionTest {

    /**
     * The class is used the way the agent uses one, then dropped. What must be
     * true afterwards is simply that it can be collected.
     */
    @Test
    void aDiscardedClassloaderIsNotHeldByTheDispatchTable() throws Exception {
        Throwaway loader = new Throwaway();
        Class<?> victim = loader.defineVictim();

        DispatchTable.ClassDispatch dispatch = DispatchTable.getOrCreate(victim);
        MethodHandle handle = MethodHandles.publicLookup()
                .findVirtual(victim, "hello", MethodType.methodType(String.class));
        dispatch.registerOverrideGuard("hello()Ljava/lang/String;", victim, "hello", handle);

        WeakReference<ClassLoader> watch = new WeakReference<>(loader);
        loader = null;
        victim = null;
        dispatch = null;
        handle = null;

        assertTrue(collected(watch),
                "the dispatch entry holds the class it is keyed by, so the whole "
                + "classloader stays for the life of the JVM");
    }

    @Test
    void aDiscardedClassloaderIsNotHeldByTheReflectionBridge() throws Exception {
        Throwaway loader = new Throwaway();
        Class<?> victim = loader.defineVictim();

        Method hello = victim.getMethod("hello");
        ReflectionBridge.replaceClassState(victim, List.of(hello), List.of());
        assertEquals(1, ReflectionBridge.getDeclaredMethods(victim).length == 0 ? 0 : 1,
                "the added member is visible while the class is alive");

        WeakReference<ClassLoader> watch = new WeakReference<>(loader);
        loader = null;
        victim = null;
        hello = null;

        assertTrue(collected(watch),
                "a forged Method holds its declaring class, so keeping it in a "
                + "map keyed by class name keeps the classloader too");
    }

    @Test
    void aDiscardedClassloaderIsNotHeldByTheFieldStore() throws Exception {
        Throwaway loader = new Throwaway();
        Class<?> victim = loader.defineVictim();

        // The lookup a field access makes, twice: the first call registers the
        // field and returns the default without resolving anything, and it is
        // the second one that reaches the class and caches what it found.
        Object instance = victim.getConstructor().newInstance();
        String internalName = victim.getName().replace('.', '/');
        FieldStore.getExtField(instance, internalName, "added", "Ljava/lang/String;");
        FieldStore.getExtField(instance, internalName, "added", "Ljava/lang/String;");

        WeakReference<Object> instanceWatch = new WeakReference<>(instance);
        instance = null;

        WeakReference<ClassLoader> watch = new WeakReference<>(loader);
        loader = null;
        victim = null;

        assertTrue(collected(watch), "a cache keyed by Class keeps every class it ever saw");
        assertNull(instanceWatch.get(), "and with it every instance the class still references");
    }

    /**
     * A check that can never fail is worth nothing, and this one would look
     * identical either way: a passing test here could mean the holding was
     * removed, or that the reference was never held long enough to notice.
     *
     * So the exact shape that was removed, a static map keyed by Class, is
     * built on purpose and the check has to catch it.
     */
    @Test
    void theCheckItselfNoticesSomethingBeingHeld() throws Exception {
        Throwaway loader = new Throwaway();
        Class<?> victim = loader.defineVictim();
        HELD_ON_PURPOSE.put(victim, "the shape this class exists to prevent");

        WeakReference<ClassLoader> watch = new WeakReference<>(loader);
        loader = null;
        victim = null;

        try {
            assertFalse(collected(watch),
                    "a Class used as a key in a live static map cannot be collected, "
                    + "so a check that reports it as collected is not checking anything");
        } finally {
            HELD_ON_PURPOSE.clear();
        }
    }

    private static final java.util.Map<Class<?>, String> HELD_ON_PURPOSE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A collection is not promised on demand, so this asks repeatedly rather
     * than once. A reference that is genuinely unreachable clears in the first
     * cycle or two; one that is held never clears, however long the loop runs.
     */
    private static boolean collected(WeakReference<?> reference) throws InterruptedException {
        for (int attempt = 0; attempt < 25; attempt++) {
            System.gc();
            if (reference.get() == null) return true;
            Thread.sleep(20);
        }
        return false;
    }

    /**
     * Loads one class by hand so that dropping this loader drops the class,
     * the way undeploying a web application does.
     */
    private static final class Throwaway extends ClassLoader {

        Throwaway() {
            super(ClassloaderRetentionTest.class.getClassLoader());
        }

        Class<?> defineVictim() throws Exception {
            String name = Victim.class.getName();
            try (InputStream in = getParent().getResourceAsStream(
                    name.replace('.', '/') + ".class")) {
                byte[] bytes = in.readAllBytes();
                // Defined here rather than delegated, so this loader owns it.
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
    }

    /**
     * Stands in for a class from a redeployed application, carrying the field
     * a structural reload gives an instrumented class. Without it the field
     * store has nothing to cache and the test proves nothing.
     */
    public static class Victim {
        public Object[] __reclazz$ext;

        public String hello() {
            return "hello";
        }
    }
}
