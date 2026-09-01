/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring answers "what does this class need injected" once per bean and keeps
 * it, which makes adding {@code @Autowired} to a field that was already there
 * one of the quietest failures a reload can have: the class reloads, the
 * annotation is on it, the bean is destroyed and re-created, and the field is
 * still null. Measured on Spring Boot 3.3.4 before this existed, and measured
 * again after it: the same edit injects.
 *
 * <p>Four caches on two processors, and they do not all sit on the same class:
 * the lifecycle one belongs to a superclass, which is why the walk goes up.
 * What these tests hold is that all of them are found, that an empty one is
 * not reported as work done, and that a field which merely shares a name is
 * left alone.
 */
class SpringInjectionMetadataReloaderTest {

    /** AutowiredAnnotationBeanPostProcessor: two, side by side. */
    static class AutowiredProcessorShape {
        private final Map<String, Object> injectionMetadataCache = new ConcurrentHashMap<>();
        private final Map<Class<?>, Object> candidateConstructorsCache = new ConcurrentHashMap<>();

        Map<String, Object> injection() {
            return injectionMetadataCache;
        }

        Map<Class<?>, Object> constructors() {
            return candidateConstructorsCache;
        }
    }

    /** InitDestroyAnnotationBeanPostProcessor: the lifecycle one lives here. */
    static class LifecycleProcessorShape {
        private final Map<Class<?>, Object> lifecycleMetadataCache = new HashMap<>();

        Map<Class<?>, Object> lifecycle() {
            return lifecycleMetadataCache;
        }
    }

    /** CommonAnnotationBeanPostProcessor: its own, plus its parent's. */
    static class CommonProcessorShape extends LifecycleProcessorShape {
        private final Map<String, Object> injectionMetadataCache = new HashMap<>();

        Map<String, Object> injection() {
            return injectionMetadataCache;
        }
    }

    /** The name without the shape: not a cache, not ours to touch. */
    static class NotACache {
        @SuppressWarnings("unused")
        private final String injectionMetadataCache = "a string";
    }

    @Test
    void bothOfTheAutowiredProcessorsCachesAreEmptied() {
        AutowiredProcessorShape processor = new AutowiredProcessorShape();
        processor.injection().put("consumer", "stale");
        processor.constructors().put(String.class, "stale");

        assertEquals(2, SpringInjectionMetadataReloader.clearCaches(processor),
                "an @Autowired moved onto a constructor changes the second one");
        assertTrue(processor.injection().isEmpty());
        assertTrue(processor.constructors().isEmpty());
    }

    /**
     * {@code @PostConstruct} is cached on a superclass of the processor that
     * caches {@code @Resource}, so stopping at the declaring class would find
     * one and miss the other.
     */
    @Test
    void theLifecycleCacheOnTheSuperclassIsFoundToo() {
        CommonProcessorShape processor = new CommonProcessorShape();
        processor.injection().put("consumer", "stale");
        processor.lifecycle().put(String.class, "stale");

        assertEquals(2, SpringInjectionMetadataReloader.clearCaches(processor));
        assertTrue(processor.injection().isEmpty());
        assertTrue(processor.lifecycle().isEmpty());
    }

    /** An empty cache is not work done, and counting it would say it was. */
    @Test
    void anEmptyCacheIsNotCounted() {
        assertEquals(0, SpringInjectionMetadataReloader.clearCaches(
                new AutowiredProcessorShape()));
    }

    @Test
    void aFieldThatOnlySharesTheNameIsLeftAlone() {
        assertEquals(0, SpringInjectionMetadataReloader.clearCaches(new NotACache()));
    }

    /** A processor shape this does not know changes nothing and throws nothing. */
    @Test
    void anUnknownShapeIsNotAFailure() {
        assertEquals(0, SpringInjectionMetadataReloader.clearCaches(new Object()));
    }
}
