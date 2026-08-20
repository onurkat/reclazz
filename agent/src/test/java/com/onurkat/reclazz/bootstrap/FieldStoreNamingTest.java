/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two sides of an added field do not spell the class the same way.
 *
 * A companion's bytecode carries the internal name it was generated from,
 * {@code demo/GreetService}. A call site the bootstrap sets up, which is what a
 * constructor's assignment becomes, carries the binary name,
 * {@code demo.GreetService}. While those were separate keys they meant separate
 * storage, so a field added by a reload and assigned in a constructor was
 * written under one name and read back under the other.
 *
 * Seen as: a newly created bean whose constructor sets the new field reads it
 * back as null. Reproduced on a Spring Boot application, and it is what the
 * integration suite's constructor scenario had been failing on.
 */
class FieldStoreNamingTest {

    private static final String INTERNAL = "demo/NamingProbe";
    private static final String BINARY = "demo.NamingProbe";

    @Test
    void bothSpellingsOfAClassNameAreTheSameField() {
        int fromInternal = FieldStore.registerField(INTERNAL, "greeting", "Ljava/lang/String;");
        int fromBinary = FieldStore.getIndex(BINARY, "greeting", "Ljava/lang/String;");

        assertEquals(fromInternal, fromBinary,
                "a constructor writes through the binary name and the companion "
                + "reads through the internal one; two indexes means two fields");
    }

    @Test
    void registeringUnderEitherSpellingDoesNotDuplicate() {
        FieldStore.registerField("demo/DuplicateProbe", "a", "I");
        FieldStore.registerField("demo.DuplicateProbe", "a", "I");

        assertEquals(1, FieldStore.getFieldCount("demo/DuplicateProbe"));
        assertEquals(1, FieldStore.getFieldCount("demo.DuplicateProbe"));
    }

    /**
     * The spelling problem cannot reach a static field at all: its storage is
     * keyed by the owning {@code Class}, one identity with no string form to
     * disagree on. The companion loads that class as a constant and the store
     * uses it directly, so a write and a read of the same class are the same
     * field by construction, and two different classes are two different
     * fields even when the field name matches.
     */
    @Test
    void aStaticFieldIsKeyedByItsOwningClassNotItsSpelling() {
        FieldStore.putStaticExtField(StaticProbe.class, "count", "I", 7);
        assertEquals(7, FieldStore.getStaticExtField(StaticProbe.class, "count", "I"),
                "the same class round-trips the same field");

        assertEquals(0, FieldStore.getStaticExtField(OtherProbe.class, "count", "I"),
                "a same-named field on a different class is a different field");
    }

    static class StaticProbe {}
    static class OtherProbe {}
}
