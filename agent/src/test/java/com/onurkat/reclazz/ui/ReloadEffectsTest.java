/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tail of the reload line: what the framework steps did, in a few words,
 * and whether anything new waits on a restart.
 */
class ReloadEffectsTest {

    @Test
    void effectsAreListedInTheOrderTheyHappened() {
        ReloadEffects.begin();
        ReloadEffects.note("bean orderService re-created");
        ReloadEffects.note("mappings re-scanned");
        ReloadEffects.note("caches evicted");

        assertEquals("bean orderService re-created, mappings re-scanned, caches evicted",
                ReloadEffects.end());
    }

    @Test
    void nothingNotedIsAnEmptyTailSoAPlainClassReadsAsItAlwaysDid() {
        ReloadEffects.begin();
        assertEquals("", ReloadEffects.end());
    }

    @Test
    void aNoteOutsideAReloadIsDroppedRatherThanBlamedOnTheNextOne() {
        ReloadEffects.begin();
        ReloadEffects.end();
        ReloadEffects.note("stray");

        ReloadEffects.begin();
        assertEquals("", ReloadEffects.end());
    }

    @Test
    void theSameEffectTwiceIsSaidOnce() {
        ReloadEffects.begin();
        ReloadEffects.note("caches evicted");
        ReloadEffects.note("caches evicted");
        assertEquals("caches evicted", ReloadEffects.end());
    }

    @Test
    void somethingNewWaitingOnARestartIsSaidWithWhereToAsk() {
        assertEquals("bean a re-created; 1 thing now waits on a restart, ask PENDING",
                ReloadEffects.digest(List.of("bean a re-created"), 1));
        assertEquals("2 things now wait on a restart, ask PENDING",
                ReloadEffects.digest(List.of(), 2));
    }

    /** A reload that adds to the ledger says so; one that does not, does not. */
    @Test
    void theLedgerIsReadAsADeltaAcrossTheReload() {
        RestartLedger.clear();
        RestartLedger.note("demo.Before", "noted before this reload");

        ReloadEffects.begin();
        ReloadEffects.note("bean a re-created");
        RestartLedger.note("demo.A", "changed its superclass");
        assertTrue(ReloadEffects.end().endsWith("1 thing now waits on a restart, ask PENDING"));

        ReloadEffects.begin();
        assertEquals("", ReloadEffects.end(), "the earlier notes are not this reload's");
        RestartLedger.clear();
    }
}
