/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The added-static store must not outlive the class whose field it holds.
 *
 * <p>A static field a reload adds has no instance to hang off, so its value is
 * kept for it on the side. The value is an arbitrary application object, and
 * for an appended enum constant it is an <em>instance of the class itself</em>.
 * While that value lived in a JVM-global map keyed by class name, the map held
 * the value, the value held its class, and the class held its classloader: a
 * webapp loader that Tomcat discards on redeploy could then never be collected,
 * which on a real install is a leak of the whole application. Measured before
 * the fix on a throwaway loader: not collectable.
 *
 * <p>The store is now a {@link ClassValue} keyed by the owning class, so its
 * per-class entry lives on that class and is collected with it. This test
 * stores, as a class's own added-static value, an instance of that class
 * defined by a throwaway loader (the enum-constant shape), drops the loader,
 * and asserts it collects. The companion tests in {@code FieldStoreNamingTest}
 * and {@code StaticInitialiserTest} hold that the values still round-trip; this
 * one holds that keeping them costs nothing once the class is gone.
 */
class FieldStoreStaticRetentionTest {

    /** A fixture with a no-arg constructor, so a throwaway loader can build one. */
    public static class Leaky {
        @SuppressWarnings("unused")
        public Object payload;
    }

    @Test
    void aStaticExtValueDoesNotPinTheOwningLoader() throws Exception {
        WeakReference<ClassLoader> watch = storeSelfInThrowawayLoader();

        for (int attempt = 0; attempt < 25 && watch.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(watch.get(),
                "an added-static value that is an instance of a discardable-loader class "
                + "must not pin that loader; the appended-enum-constant leak was exactly this");
    }

    /**
     * Isolated so the only strong reference to the loader is the one this
     * method drops on return: a class defined by a throwaway loader, an
     * instance of it stored as its own added-static value, then let go. The
     * value is verified present before the loader is dropped, so a passing
     * collection cannot be an empty store quietly holding nothing.
     */
    private static WeakReference<ClassLoader> storeSelfInThrowawayLoader() throws Exception {
        String name = Leaky.class.getName();
        byte[] bytes;
        try (InputStream in = FieldStoreStaticRetentionTest.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(in, "cannot read fixture bytecode");
            bytes = in.readAllBytes();
        }
        ClassLoader loader = new ClassLoader(FieldStoreStaticRetentionTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> victim = (Class<?>) loader.getClass().getDeclaredMethod("define").invoke(loader);
        assertNotSame(Leaky.class, victim, "the fixture must come from the throwaway loader, not the test's");

        Object instance = victim.getDeclaredConstructor().newInstance();
        String desc = "L" + name.replace('.', '/') + ";";
        FieldStore.putStaticExtField(victim, "SELF", desc, instance);

        assertSame(instance, FieldStore.getStaticExtField(victim, "SELF", desc),
                "the value must genuinely be stored, or this test would prove nothing");
        assertTrue(FieldStore.isStaticInitialised(victim, "SELF", desc));
        return new WeakReference<>(loader);
    }
}
