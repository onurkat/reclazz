/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.AgentSources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One reload batch at a time, which everything downstream is written for.
 *
 * <p>A reload does not only redefine a class. It clears and refills state
 * shared across the whole application: injection metadata, mapping registries,
 * the validator's constraint caches, the security metadata, the AOP advisor
 * cache. Every one of those reloaders is written as though it were the only
 * thing running, because it is, and the only reason it is, is that the reload
 * executor has one thread.
 *
 * <p>That makes the thread count load-bearing while looking like a resource
 * decision, which is the kind of thing a later change makes in passing. Turning
 * it into a pool would not fail here, or in the integration suite, or on the
 * first hundred reloads; it would show up as a mapping registry that lost an
 * entry on a save that touched two controllers at once.
 */
class ReloadsAreSerialisedTest {

    @Test
    void theReloadExecutorHasOneThread() throws IOException {
        Path agent = AgentSources.root().resolve("com/onurkat/reclazz/agent/ReclazzAgent.java");

        String creation = Files.readAllLines(agent).stream()
                .filter(line -> line.contains("reloadExecutor = Executors."))
                .findFirst()
                .orElse(null);

        assertNotNull(creation,
                "the reload executor is not created where this test knows to look; if it moved, "
                        + "move this with it rather than deleting it");
        assertTrue(creation.contains("newSingleThreadExecutor"),
                () -> "reload batches are no longer serialised, and every reloader downstream "
                        + "assumes they are: " + creation.trim());
    }

    /**
     * Identified by a file that has to be in it: the repository root holds the
     * IntelliJ plugin under the same {@code src/main/java}, and a scan that
     * finds the wrong tree passes without reading anything.
     */
}
