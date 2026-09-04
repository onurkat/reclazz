/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The third question the agent can be asked.
 *
 * <p>The other two are about a particular thing: why that class did not reload,
 * and what a restart would still change. Neither answers the plainest one. A
 * developer whose saves feel slow has no number of their own, only the one in
 * the README, measured on somebody else's machine. A developer who thinks
 * nothing is reloading cannot tell a watcher that never saw their directory
 * from reloads landing on code they are not exercising, and the difference
 * between those two is an afternoon.
 *
 * <p>What it must not do is guess. A session that has reloaded nothing has to
 * say so rather than report a median of zero.
 */
class SessionReportTest {

    @BeforeEach
    void freshSession() {
        SessionReport.resetForTests();
    }

    private static String reportOf(int watched, int unwatchable, int restarts) {
        return String.join("\n", SessionReport.lines(watched, unwatchable, restarts));
    }

    @Test
    void aSessionThatHasDoneNothingSaysSo() {
        String said = reportOf(212, 0, 0);

        assertTrue(said.contains("Nothing has reloaded yet"),
                () -> "a fresh session must not report a median of nothing: " + said);
        assertFalse(said.contains("median"), () -> said);
        assertTrue(said.contains("No file has changed"), () -> said);
        assertTrue(said.contains("Nothing is waiting on a restart"), () -> said);
    }

    @Test
    void theCountsSplitStructuralFromBodies() {
        SessionReport.reloaded(true, 140);
        SessionReport.reloaded(false, 60);
        SessionReport.reloaded(false, 80);

        String said = reportOf(212, 0, 0);

        assertTrue(said.contains("3 reloads"), () -> said);
        assertTrue(said.contains("1 structural"), () -> said);
        assertTrue(said.contains("2 method bodies"), () -> said);
    }

    @Test
    void aFailureIsCountedAndNamed() {
        SessionReport.reloaded(false, 50);
        SessionReport.failed();

        String said = reportOf(1, 0, 0);

        assertTrue(said.contains("1 failure"), () -> said);
    }

    /** The number the README quotes, for this machine and this session. */
    @Test
    void theLatenciesAreReportedAsADistributionRatherThanAnAverage() {
        for (long ms : new long[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 4000}) {
            SessionReport.reloaded(false, ms);
        }

        String said = reportOf(1, 0, 0);

        assertTrue(said.contains("60ms median"), () -> said);
        assertTrue(said.contains("4000ms worst"),
                () -> "the slow one is the whole reason for not reporting an average: " + said);
        assertTrue(said.contains("95th"), () -> said);
    }

    /** A reload the caller did not time must not count as one that took nothing. */
    @Test
    void anUntimedReloadIsCountedButNotTimed() {
        SessionReport.reloaded(true, -1);

        String said = reportOf(1, 0, 0);

        assertTrue(said.contains("1 reload"), () -> said);
        assertTrue(said.contains("None of them was timed"),
                () -> "the batch path passes -1, and a median of zero would be a lie: " + said);
    }

    /**
     * Most reloads on SAP Commerce go through the batch path, which times the
     * batch and not the classes in it. Measured live: 353 of 379. So the line
     * has to say what the number is drawn from, or "over the last 26 reloads"
     * beside "379 reloads" reads as a contradiction.
     */
    @Test
    void theLatencyLineSaysHowManyOfThemItSpeaksFor() {
        for (int i = 0; i < 30; i++) SessionReport.reloaded(false, -1);
        for (int i = 0; i < 4; i++) SessionReport.reloaded(false, 200);

        String said = reportOf(1, 0, 0);

        assertTrue(said.contains("34 reloads"), () -> said);
        assertTrue(said.contains("over the last 4 of 34"),
                () -> "the median is drawn from four of them and must say so: " + said);
    }

    @Test
    void whenEveryReloadWasTimedItJustSaysHowMany() {
        for (int i = 0; i < 5; i++) SessionReport.reloaded(false, 100);

        String said = reportOf(1, 0, 0);

        assertTrue(said.contains("over all 5 of them"), () -> said);
        assertFalse(said.contains("the last"),
                () -> "there is nothing to explain when the number speaks for all of them: " + said);
    }

    /**
     * The line that separates "Reclazz is broken" from "you edited something
     * nobody is watching", which is the commonest confusion there is.
     */
    @Test
    void whatCouldNotBeWatchedIsInTheReport() {
        String said = reportOf(1994, 228, 0);

        assertTrue(said.contains("1994 directories watched"), () -> said);
        assertTrue(said.contains("228"), () -> said);
        assertTrue(said.contains("reloads nothing"),
                () -> "and what that number means for them: " + said);
    }

    @Test
    void aQuietWatcherIsNotAWatcherWithNothingToSay() {
        String said = reportOf(0, 0, 0);

        assertTrue(said.contains("0 directories watched"),
                () -> "watching nothing is the answer, not the absence of one: " + said);
    }

    @Test
    void whatIsWaitingOnARestartPointsAtTheQuestionThatListsIt() {
        String said = reportOf(1, 0, 3);

        assertTrue(said.contains("3 things waiting on a restart"), () -> said);
        assertTrue(said.contains("PENDING"),
                () -> "and where to get the list, which this deliberately does not repeat: " + said);
    }

    @Test
    void theVersionAndTheUptimeOpenIt() {
        String said = reportOf(1, 0, 0);

        assertTrue(said.startsWith("Reclazz "), () -> said);
        assertTrue(said.contains("up "),
                () -> "how long it has been up is the first thing that dates everything else: "
                        + said);
    }

    @Test
    void durationsAreSaidTheWayAPersonSaysThem() {
        assertEquals("1 second", SessionReport.humanDuration(Duration.ofSeconds(1)));
        assertEquals("45 seconds", SessionReport.humanDuration(Duration.ofSeconds(45)));
        assertEquals("1 minute", SessionReport.humanDuration(Duration.ofSeconds(90)));
        assertEquals("3 hours", SessionReport.humanDuration(Duration.ofMinutes(200)));
        assertEquals("2 days", SessionReport.humanDuration(Duration.ofHours(50)));
        assertEquals("0 seconds", SessionReport.humanDuration(Duration.ofSeconds(-5)),
                "a clock that went backwards is not a negative age");
    }

    /** Nothing here may be expensive: it is asked from a socket handler. */
    @Test
    void theRingForgetsRatherThanGrowing() {
        for (int i = 0; i < 5000; i++) {
            SessionReport.reloaded(false, 100);
        }

        List<String> said = SessionReport.lines(1, 0, 0);

        assertTrue(String.join("\n", said).contains("5000 reloads"),
                () -> "the count is all of them: " + said);
        assertTrue(String.join("\n", said).contains("over the last 512 of 5000"),
                () -> "and the timings are the last few hundred, said as such: " + said);
    }
}
