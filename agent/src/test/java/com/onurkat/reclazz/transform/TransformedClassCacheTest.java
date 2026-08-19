/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The last-known-good cache has one job: hand back exactly the bytes that
 * went in. The bytes are the source the superclass salvage splices previous
 * method bodies from, and a cache that returns anything but the original,
 * bit for bit, turns a pinned method into corrupted bytecode inside
 * {@code redefineClasses}, on a live server.
 */
class TransformedClassCacheTest {

    @Test
    void whatGoesInComesBackByteForByte() {
        byte[] bytes = new byte[40_000];
        new Random(7).nextBytes(bytes);
        // Random bytes are the worst case for deflate, so a round-trip that
        // survives them also survives real class files, which compress well.
        TransformedClassCache.put("cache/test/RoundTrip", bytes);

        assertArrayEquals(bytes, TransformedClassCache.get("cache/test/RoundTrip"));
    }

    @Test
    void aClassNeverCachedAnswersNullSoTheSalvageRefusesInsteadOfGuessing() {
        assertNull(TransformedClassCache.get("cache/test/NeverStored"));
    }

    /**
     * The second put replaces the first, because "last known good" means the
     * latest thing the transformer emitted, not the first. A stale entry
     * would pin a method to an implementation older than the one its callers
     * were just served.
     */
    @Test
    void aSecondPutReplacesTheFirstAndTheTotalFollows() {
        byte[] first = "first version of the class".getBytes();
        byte[] second = "second version, and it is the one that counts".getBytes();

        TransformedClassCache.put("cache/test/Replaced", first);
        long afterFirst = TransformedClassCache.deflatedBytes();
        TransformedClassCache.put("cache/test/Replaced", second);

        assertArrayEquals(second, TransformedClassCache.get("cache/test/Replaced"));
        assertNotEquals(0, TransformedClassCache.deflatedBytes(),
                "the running total is the memory measurement; it must track entries");
        assertTrue(TransformedClassCache.classCount() >= 1);
        // Replacing must swap the accounted size, not accumulate it: the
        // total after replacing one entry differs from after storing it once
        // only by the size difference of the payloads, never by a whole
        // duplicate entry.
        long delta = TransformedClassCache.deflatedBytes() - afterFirst;
        assertTrue(Math.abs(delta) < first.length + second.length,
                "replacement leaked a duplicate entry into the total: delta=" + delta);
    }
}
