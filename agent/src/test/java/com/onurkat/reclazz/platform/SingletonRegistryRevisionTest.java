/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.transform.SingletonRegistryRevisionTransformer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import static org.junit.jupiter.api.Assertions.*;

class SingletonRegistryRevisionTest {
    @Test void nativeSpringAddRemoveAndClearInvalidateTheObservedRegistry() throws Exception {
        String name = DefaultSingletonBeanRegistry.class.getName();
        String internal = name.replace('.', '/');
        class Loader extends ClassLoader {
            Loader() { super(DefaultSingletonBeanRegistry.class.getClassLoader()); }
            Class<?> define(byte[] code) { return defineClass(name, code, 0, code.length); }
        }
        var loader = new Loader();
        byte[] code;
        try (var input = loader.getResourceAsStream(internal + ".class")) {
            assertNotNull(input);
            code = new SingletonRegistryRevisionTransformer().transform(loader, internal, null, null, input.readAllBytes());
        }
        assertNotNull(code);
        Class<?> type = loader.define(code);
        Object registry = type.getConstructor().newInstance();
        assertEquals(0L, SapProcessResources.revision(registry));
        type.getMethod("registerSingleton", String.class, Object.class).invoke(registry, "first", new Object());
        long added = SapProcessResources.revision(registry);
        assertTrue(added > 0);
        type.getMethod("destroySingleton", String.class).invoke(registry, "first");
        long removed = SapProcessResources.revision(registry);
        assertTrue(removed > added);
        type.getMethod("destroySingletons").invoke(registry);
        assertTrue(SapProcessResources.revision(registry) > removed);
    }
}
