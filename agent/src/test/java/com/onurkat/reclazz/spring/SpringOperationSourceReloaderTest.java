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
 * Clearing the cached answer to "what does the annotation on this method say".
 *
 * <p>Spring keeps that answer in a map whose name is not a contract: the
 * transaction source calls it {@code attributeCache}, and the cache source
 * called it that too until Spring Framework 6.1 renamed its copy to
 * {@code operationCache}. The rename was found the hard way: a live spike
 * showed {@code @Transactional} edits taking effect while {@code @Cacheable}
 * edits silently kept the old behaviour, because only one of the two maps was
 * being found and cleared, and one success line claimed both. These tests hold
 * the finder against both names, the shape fallback for the next rename, and
 * the refusals that keep it from clearing somebody else's map.
 */
class SpringOperationSourceReloaderTest {

    /** The transaction source's shape since its fallback base class existed. */
    static class TxSourceShape {
        @SuppressWarnings("unused")
        private final Map<Object, Object> attributeCache = new ConcurrentHashMap<>();
        Map<Object, Object> cache() { return attributeCache; }
    }

    /** The cache source's shape as of Spring Framework 6.1. */
    static class CacheSourceShape {
        @SuppressWarnings("unused")
        private final Map<Object, Object> operationCache = new ConcurrentHashMap<>();
        Map<Object, Object> cache() { return operationCache; }
    }

    /** A future rename: one Map field ending in Cache, under any name. */
    static class RenamedShape {
        @SuppressWarnings("unused")
        private final Map<Object, Object> resolvedMetadataCache = new ConcurrentHashMap<>();
        Map<Object, Object> cache() { return resolvedMetadataCache; }
    }

    /** Two candidate maps: guessing between them would clear the wrong one. */
    static class AmbiguousShape {
        @SuppressWarnings("unused")
        private final Map<Object, Object> firstCache = new HashMap<>();
        @SuppressWarnings("unused")
        private final Map<Object, Object> secondCache = new HashMap<>();
    }

    /** The map lives on a base class, the way Spring's fallback sources hold it. */
    static class SubclassOfTxShape extends TxSourceShape {
    }

    @Test
    void theTransactionSourcesMapIsFoundByItsName() {
        TxSourceShape source = new TxSourceShape();
        source.cache().put("k", "stale");

        assertTrue(SpringOperationSourceReloader.clearAttributeCache(source));
        assertTrue(source.cache().isEmpty(), "the stale answer must be gone");
    }

    @Test
    void theCacheSourcesRenamedMapIsFoundToo() {
        CacheSourceShape source = new CacheSourceShape();
        source.cache().put("k", "stale");

        assertTrue(SpringOperationSourceReloader.clearAttributeCache(source),
                "operationCache is the 6.1 name whose miss was measured live");
        assertTrue(source.cache().isEmpty());
    }

    @Test
    void aFutureRenameIsCaughtByShapeWhenItIsUnambiguous() {
        RenamedShape source = new RenamedShape();
        source.cache().put("k", "stale");

        assertTrue(SpringOperationSourceReloader.clearAttributeCache(source));
        assertTrue(source.cache().isEmpty());
    }

    @Test
    void twoCandidateMapsMeanNothingIsTouched() {
        AmbiguousShape source = new AmbiguousShape();
        source.firstCache.put("a", "x");
        source.secondCache.put("b", "y");

        assertFalse(SpringOperationSourceReloader.clearAttributeCache(source),
                "clearing the wrong framework map would be worse than clearing none");
        assertEquals(1, source.firstCache.size());
        assertEquals(1, source.secondCache.size());
    }

    @Test
    void theMapIsFoundOnASuperclassLikeSpringsFallbackSources() {
        SubclassOfTxShape source = new SubclassOfTxShape();
        source.cache().put("k", "stale");

        assertTrue(SpringOperationSourceReloader.clearAttributeCache(source));
        assertTrue(source.cache().isEmpty());
    }

    @Test
    void anEmptyMapReportsNothingCleared() {
        assertFalse(SpringOperationSourceReloader.clearAttributeCache(new TxSourceShape()),
                "an empty cache cleared is not a metadata re-read worth reporting");
        assertFalse(SpringOperationSourceReloader.clearAttributeCache(null));
    }
}
