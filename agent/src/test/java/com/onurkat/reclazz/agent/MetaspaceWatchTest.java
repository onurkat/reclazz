/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** When the metaspace warning is worth interrupting for, and when it is noise. */
class MetaspaceWatchTest {

    /** Divides by 100 exactly, so the percentages in the tests are the percentages. */
    private static final long CAP = 100L * 1024 * 1024;

    @Test
    void saysNothingWhenMetaspaceIsUncapped() {
        // The default outside a container: growth is bounded by the machine
        // and there is no ceiling to warn about approaching.
        assertEquals(0, MetaspaceWatch.thresholdCrossed(percent(99), -1, 0));
        assertEquals(0, MetaspaceWatch.thresholdCrossed(percent(99), 0, 0));
    }

    @Test
    void saysNothingWithRoomLeft() {
        assertEquals(0, MetaspaceWatch.thresholdCrossed(percent(50), CAP, 0));
        assertEquals(0, MetaspaceWatch.thresholdCrossed(percent(84), CAP, 0));
    }

    @Test
    void warnsOnceAtEachThreshold() {
        assertEquals(85, MetaspaceWatch.thresholdCrossed(percent(85), CAP, 0));
        assertEquals(0, MetaspaceWatch.thresholdCrossed(percent(90), CAP, 85),
                "the same threshold twice is nagging, not information");
        assertEquals(95, MetaspaceWatch.thresholdCrossed(percent(96), CAP, 85));
        assertEquals(0, MetaspaceWatch.thresholdCrossed(percent(99), CAP, 95));
    }

    @Test
    void aSessionThatStartsAboveBothReportsTheHigherOne() {
        assertEquals(95, MetaspaceWatch.thresholdCrossed(percent(97), CAP, 0));
    }

    @Test
    void aPoolThatSaysNothingProducesNoClaim() {
        assertEquals(0, MetaspaceWatch.thresholdCrossed(0, CAP, 0));
    }

    /**
     * The entry point the reloader calls, on whatever JVM the tests run on.
     * It has to count and it has to not throw: a reload must never fail over
     * a memory pool that will not answer.
     */
    @Test
    void countingAReloadNeverThrows() {
        int before = MetaspaceWatch.reloadCount();
        MetaspaceWatch.afterReload();
        assertEquals(before + 1, MetaspaceWatch.reloadCount());
    }

    private static long percent(int p) {
        return CAP / 100 * p;
    }
}
