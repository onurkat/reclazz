/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchemaUpdateTransactionTest {
    public static class Tx {
        final List<String> events = new ArrayList<>();
        boolean running;
        public boolean isRunning() { return running; }
        public void begin() { events.add("begin"); running = true; }
        public void commit() { events.add("commit"); running = false; }
        public void rollback() { events.add("rollback"); running = false; }
    }

    @Test void freshHsqlTransactionCommitsOnlySuccessfulNativeResults() throws Exception {
        for (boolean success : List.of(true, false)) {
            var tx = new Tx();
            assertEquals(success, SchemaUpdateTransaction.call("jdbc:hsqldb:mem:test", () -> tx,
                    () -> { assertTrue(tx.running); return success; }, Boolean.TRUE::equals));
            assertEquals(List.of("begin", success ? "commit" : "rollback"), tx.events);
            assertFalse(tx.running);
        }
    }

    @Test void throwingUpdateRollsBackOwnedTransaction() {
        var tx = new Tx();
        var failure = new IllegalArgumentException();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
                SchemaUpdateTransaction.call("jdbc:hsqldb:mem:test", () -> tx, () -> { throw failure; }, ignored -> true)));
        assertEquals(List.of("begin", "rollback"), tx.events);
    }

    @Test void existingCallerTransactionRemainsOpenOnFailure() throws Exception {
        var tx = new Tx();
        tx.running = true;
        assertFalse(SchemaUpdateTransaction.call("jdbc:hsqldb:mem:test", () -> tx, () -> false, Boolean.TRUE::equals));
        assertTrue(tx.running);
        assertTrue(tx.events.isEmpty());
    }

    @Test void otherDatabasesNeverRequestANativeTransaction() throws Exception {
        assertTrue(SchemaUpdateTransaction.call("jdbc:postgresql:test", () -> fail("unexpected transaction"),
                () -> true, Boolean.TRUE::equals));
    }
}
