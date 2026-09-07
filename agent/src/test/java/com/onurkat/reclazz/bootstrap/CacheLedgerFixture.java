package com.onurkat.reclazz.bootstrap;

/** Shared reset for isolated ledger and Spring integration tests. */
public final class CacheLedgerFixture {
    private CacheLedgerFixture() { }
    public static void reset() { CacheDependencyLedger.resetForTests(); }
}
