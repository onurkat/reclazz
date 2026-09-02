/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Computing frames for classes that cannot be loaded.
 *
 * <p>{@code COMPUTE_FRAMES} asks the writer which common supertype to write
 * down where two reference types meet, and the stock answer loads them through
 * the writer's own classloader, which is the agent's. The types are the
 * application's: a companion that is not defined yet, a hidden class with no
 * name to load by, a controller on a web application's loader. The failure
 * comes out of the middle of frame computation and takes the transform with it.
 *
 * <p>Two places had written the fallback out by hand, each after hitting it.
 * The third place that computes frames had not, and it is the adapter that
 * carries an added {@code @RequestMapping} method to the mapping scan, building
 * a class that refers to the application's own controller.
 */
class SafeClassWriterTest {

    private static final String ABSENT_ONE = "com/acme/nothing/Alpha";

    private static final String ABSENT_TWO = "com/acme/nothing/Beta";

    @Test
    void theStockWriterFailsOnTypesItCannotLoad() {
        StockProbe stock = new StockProbe(ClassWriter.COMPUTE_FRAMES);

        assertThrows(Throwable.class, () -> stock.ask(ABSENT_ONE, ABSENT_TWO),
                "if this stops throwing, the fallback has stopped being needed");
    }

    @Test
    void theSafeWriterAnswersObjectInstead() {
        Probe safe = new Probe(ClassWriter.COMPUTE_FRAMES);

        assertEquals("java/lang/Object", safe.ask(ABSENT_ONE, ABSENT_TWO));
    }

    /** And it is still exact for types it can resolve. */
    @Test
    void aResolvableePairStillGetsTheRealAnswer() {
        Probe safe = new Probe(ClassWriter.COMPUTE_FRAMES);

        assertEquals("java/lang/Number",
                safe.ask("java/lang/Integer", "java/lang/Long"));
    }

    @Test
    void oneSideMissingIsEnoughToFallBack() {
        Probe safe = new Probe(ClassWriter.COMPUTE_FRAMES);

        assertEquals("java/lang/Object", safe.ask("java/lang/Integer", ABSENT_TWO));
    }

    /** Reaches the protected method the way ASM does, from inside. */
    private static final class StockProbe extends ClassWriter {
        StockProbe(int flags) {
            super(flags);
        }

        String ask(String type1, String type2) {
            return getCommonSuperClass(type1, type2);
        }
    }

    private static final class Probe extends SafeClassWriter {
        Probe(int flags) {
            super(flags);
        }

        String ask(String type1, String type2) {
            return getCommonSuperClass(type1, type2);
        }
    }
}
