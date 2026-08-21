/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.EnumSurgery;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Taking a constant off the end of a running enum.
 *
 * <p>The removal refusal was always about ordinals: taking a constant out of
 * the middle renumbers everything after it. The LAST constant has nothing
 * after it, so the survivors keep their ordinals and the append's machinery
 * runs in reverse: shrink the private array, clear the caches, done. These
 * tests hold the surgery, and hold the guards that keep it from ever becoming
 * the dangerous removal: a tail that does not match is declined untouched,
 * and an enum is never emptied.
 *
 * <p>Each test gets its own fixture enum, because the surgery is real and a
 * shared fixture would leak one test's removal into the next.
 */
class EnumTailRemovalTest {

    enum Removable1 { NEW, PAID, SHIPPED }
    enum Removable2 { NEW, PAID, SHIPPED }
    enum Removable3 { NEW, PAID }
    enum Removable4 { ONLY }

    @Test
    void theLastConstantComesOffAndNothingMoves() {
        var outcome = EnumSurgery.removeFromEnd(Removable1.class, List.of("SHIPPED"));

        assertTrue(outcome.applied(), "declined: " + outcome.declinedBecause());
        List<String> names = Arrays.stream(Removable1.class.getEnumConstants())
                .map(c -> ((Enum<?>) c).name()).toList();
        assertEquals(List.of("NEW", "PAID"), names,
                "values() must stop returning the removed constant");
        assertEquals(0, Removable1.NEW.ordinal(), "the survivors keep their ordinals");
        assertEquals(1, Removable1.PAID.ordinal());
        assertThrows(IllegalArgumentException.class,
                () -> Enum.valueOf(Removable1.class, "SHIPPED"),
                "valueOf must stop accepting the removed name, which is what removal means");
        assertNotNull(Removable1.SHIPPED,
                "the instance itself survives for whoever already holds it");
        assertEquals(2, Removable1.SHIPPED.ordinal(), "and keeps the identity it had");
    }

    @Test
    void twoConstantsComeOffTheEndTogether() {
        var outcome = EnumSurgery.removeFromEnd(Removable2.class, List.of("PAID", "SHIPPED"));

        assertTrue(outcome.applied(), "declined: " + outcome.declinedBecause());
        assertEquals(1, Removable2.class.getEnumConstants().length);
        assertEquals("NEW", ((Enum<?>) Removable2.class.getEnumConstants()[0]).name());
    }

    @Test
    void aTailThatDoesNotMatchIsDeclinedUntouched() {
        var outcome = EnumSurgery.removeFromEnd(Removable3.class, List.of("NEW"));

        assertFalse(outcome.applied(),
                "NEW is not the tail; removing it would renumber PAID");
        assertEquals(2, Removable3.class.getEnumConstants().length,
                "a declined removal must leave the enum exactly as it was");
        assertEquals("PAID", Enum.valueOf(Removable3.class, "PAID").name());
    }

    @Test
    void theEnumIsNeverEmptied() {
        var outcome = EnumSurgery.removeFromEnd(Removable4.class, List.of("ONLY"));

        assertFalse(outcome.applied(), "an enum with no constants is not a thing the JVM runs");
        assertEquals(1, Removable4.class.getEnumConstants().length);
    }

    @Test
    void nothingToRemoveIsDeclinedNotThrown() {
        assertFalse(EnumSurgery.removeFromEnd(null, List.of("X")).applied());
        assertFalse(EnumSurgery.removeFromEnd(String.class, List.of("X")).applied());
        assertFalse(EnumSurgery.removeFromEnd(Removable3.class, List.of()).applied());
    }
}
