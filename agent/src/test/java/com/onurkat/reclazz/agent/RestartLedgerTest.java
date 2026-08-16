package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reclazz warns about each of these once, as it happens, in a log that keeps
 * moving. The value here is entirely in what the developer can ask for later,
 * so what these tests protect is the answer being usable: no repetition, and a
 * clear statement when there is nothing to report.
 */
class RestartLedgerTest {

    @BeforeEach
    void reset() {
        RestartLedger.clear();
    }

    @Test
    void withNothingToReportItSaysSoPlainly() {
        String digest = String.join("\n", RestartLedger.digest());

        assertTrue(digest.contains("Nothing from this session needs a restart"), digest);
    }

    @Test
    void anEntryCarriesItsSubjectAndReason() {
        RestartLedger.note("demo.Counter", "added static field(s) [TOTAL] that read as null/0");

        String digest = String.join("\n", RestartLedger.digest());

        assertTrue(digest.contains("demo.Counter"), digest);
        assertTrue(digest.contains("TOTAL"), digest);
        assertTrue(digest.contains("One thing"), digest);
    }

    /**
     * The same class reloaded twenty times would otherwise report the same
     * static field twenty times, which buries the one entry that is different.
     */
    @Test
    void theSameConcernIsOneEntryHoweverOftenItHappens() {
        for (int i = 0; i < 20; i++) {
            RestartLedger.note("demo.Counter", "added static field(s) [TOTAL] that read as null/0");
        }

        assertEquals(1, RestartLedger.size());
        assertTrue(String.join("\n", RestartLedger.digest()).contains("20 times"),
                "how often it happened is worth keeping, as one line");
    }

    @Test
    void differentConcernsAboutOneClassStaySeparate() {
        RestartLedger.note("demo.Status", "added static field(s) [CACHE] that read as null/0");
        RestartLedger.note("demo.Status", "gained enum value(s) [PAUSED], which a running JVM cannot add");

        assertEquals(2, RestartLedger.size(),
                "one is waiting for a value, the other cannot work at all");
    }

    /**
     * A session left running for days must not turn its own warnings into a
     * leak.
     */
    @Test
    void theLedgerIsBounded() {
        for (int i = 0; i < 500; i++) {
            RestartLedger.note("demo.Class" + i, "added a static field");
        }

        assertTrue(RestartLedger.size() <= 200, "was " + RestartLedger.size());
    }
}
