/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import com.acme.outside.Intruder;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who may be handed a captured lookup.
 *
 * <p>The value is a class's own {@code MethodHandles.lookup()}, which carries
 * private access to that class, and on a classpath application
 * {@code privateLookupIn} turns one of those into private access to every other
 * class on the classpath. This class sits on the bootstrap classloader, so
 * without a check every line of code in the JVM could ask for it: an expression
 * language evaluating a submitted string, a deserialization gadget able to call
 * a static method, application code that has no business with any of it.
 *
 * <p>None of those can write a class file into a watched directory, which is
 * the boundary the README draws around what running under Reclazz means. A
 * public method handing out the same reach moved that boundary without saying
 * so.
 */
class LookupCaptureTest {

    private static final MethodHandles.Lookup MINE = MethodHandles.lookup();

    /**
     * The check begins at sealing, which is the moment the agent finishes
     * starting and the application takes over. Before it there is no
     * application code to keep out, which is why the engine's own tests can
     * drive it directly without holding a key that would work in production.
     */
    @Test
    void applicationCodeIsRefusedTheLookup() {
        LookupCapture.trust(LookupCaptureTest.class);
        LookupCapture.store(LookupCaptureTest.class, MINE);
        try {
            LookupCapture.seal();

            SecurityException refused = assertThrows(SecurityException.class,
                    () -> Intruder.steal(LookupCaptureTest.class));
            assertTrue(refused.getMessage().contains("Intruder"),
                    () -> "the refusal should name who asked: " + refused.getMessage());
        } finally {
            LookupCapture.unsealForTests();
        }
    }

    @Test
    void applicationCodeCannotPlantOneEither() {
        try {
            LookupCapture.seal();

            assertThrows(SecurityException.class,
                    () -> Intruder.poison(LookupCaptureTest.class, MINE),
                    "an engine that can be handed a lookup of someone else's choosing "
                            + "is an engine doing its work with it");
        } finally {
            LookupCapture.unsealForTests();
        }
    }

    /** Sealed, the engine's own registered classes still get through. */
    @Test
    void theEngineStillWorksAfterSealing() {
        LookupCapture.trust(LookupCaptureTest.class);
        LookupCapture.store(LookupCaptureTest.class, MINE);
        try {
            LookupCapture.seal();

            assertSame(MINE, LookupCapture.get(LookupCaptureTest.class));
        } finally {
            LookupCapture.unsealForTests();
        }
    }

    @Test
    void theEngineGetsWhatItCaptured() {
        LookupCapture.trust(LookupCaptureTest.class);
        LookupCapture.store(LookupCaptureTest.class, MINE);

        assertSame(MINE, LookupCapture.get(LookupCaptureTest.class));
    }

    @Test
    void aClassNobodyCapturedForAnswersNothingRatherThanRefusing() {
        LookupCapture.trust(LookupCaptureTest.class);

        assertNull(LookupCapture.get(String.class),
                "not having a lookup and not being allowed one are different answers");
    }

    /**
     * The list is closed once the agent has named its own classes, so nothing
     * that loads afterwards can add itself, engine-shaped name or not.
     */
    @Test
    void theListCannotBeAddedToOnceSealed() {
        try {
            LookupCapture.seal();
            assertThrows(SecurityException.class,
                    () -> LookupCapture.trust(Intruder.class));
        } finally {
            LookupCapture.unsealForTests();
        }
    }
}
