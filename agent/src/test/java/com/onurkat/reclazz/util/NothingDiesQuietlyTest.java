/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import com.onurkat.reclazz.agent.RestartLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The failure this project keeps having to hunt down: not a crash, a quiet
 * stop.
 *
 * <p>The agent's long-running work is handed to an executor with
 * {@code submit}, which captures anything thrown into a {@code Future}, and
 * nobody holds these futures. The first test here is that mechanism, on a real
 * executor, and it is what the file watcher was standing on: a runtime
 * exception ended the thread, the exception went into an object that was
 * discarded on the next line, and the session carried on with saving a file
 * doing nothing at all. The IDE still showed a connected agent, because the
 * heartbeat runs on a different thread and it was fine.
 *
 * <p>What gets said matters as much as that something is. A developer whose
 * watcher has died is not asking what threw; they are asking whether their next
 * save will do anything. So the message is the consequence, and it reaches the
 * restart ledger, which is what answers "do I still need to restart?".
 */
class NothingDiesQuietlyTest {

    @BeforeEach
    void freshSession() {
        RestartLedger.clear();
    }

    /**
     * The hole itself, shown rather than described: an executor swallows it
     * whole, and this is what everything below is protecting against.
     */
    @Test
    void anExecutorSwallowsWhatItIsGivenAndSaysNothing() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        StringBuilder heard = new StringBuilder();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> heard.append(e));

        try {
            executor.submit(() -> {
                throw new IllegalStateException("the watcher gave up");
            });
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals("", heard.toString(),
                    "submit() captures it into a Future, so not even the default handler "
                            + "hears about it, which is why this needed wrapping at all");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(null);
            executor.shutdownNow();
        }
    }

    @Test
    void workThatShouldRunForeverSaysSoWhenItStops() {
        String said = printed(() -> Supervised.forever("The file watcher",
                "Nothing will reload until this application is restarted.",
                () -> {
                    throw new IllegalStateException("watch service closed");
                }).run());

        assertTrue(said.contains("The file watcher stopped"),
                () -> "named as the thing the developer relies on: " + said);
        assertTrue(said.contains("Nothing will reload"),
                () -> "and what it costs them, which is the part they act on: " + said);
        assertTrue(said.contains("watch service closed"),
                () -> "with what actually happened, for the report they will file: " + said);
    }

    /**
     * An Error is what a task like this dies of. Catching Exception is how the
     * whole thing stayed invisible in the first place.
     */
    @Test
    void anErrorIsCaughtToo() {
        String said = printed(() -> Supervised.forever("The file watcher", "Restart it.",
                () -> {
                    throw new NoClassDefFoundError("sun/nio/fs/PollingWatchService");
                }).run());

        assertTrue(said.contains("The file watcher stopped"), () -> said);
        assertTrue(said.contains("PollingWatchService"), () -> said);
    }

    /** And it reaches the answer to "do I still need to restart?". */
    @Test
    void aStoppedWatcherIsRememberedForTheRestartSummary() {
        printed(() -> Supervised.forever("The file watcher", "Restart it.",
                () -> {
                    throw new IllegalStateException("gone");
                }).run());

        assertTrue(ledgerNames("The file watcher"),
                "a session that stopped reloading is the clearest possible reason to restart");
    }

    /** A stop with no exception behind it reads the same way. */
    @Test
    void endingQuietlyIsReportedAsAStopToo() {
        String said = printed(() -> Supervised.stoppedUnexpectedly("The file watcher",
                "Nothing will reload until this application is restarted."));

        assertTrue(said.contains("The file watcher stopped"), () -> said);
        assertTrue(said.contains("Nothing will reload"), () -> said);
        assertTrue(ledgerNames("The file watcher"));
    }

    /**
     * One piece of work among many is a different thing. Losing one save is
     * not losing the session, and saying "restart" about it would be wrong.
     */
    @Test
    void oneFailedPieceOfWorkIsNotTheEndOfTheSession() {
        String said = printed(() -> Supervised.once("Handling Order.class",
                () -> {
                    throw new IllegalStateException("bad bytes");
                }).run());

        assertTrue(said.contains("Handling Order.class failed"), () -> said);
        assertFalse(said.contains("restart"),
                () -> "one save going wrong does not call for a restart: " + said);
        assertFalse(ledgerNames("Handling Order.class"),
                "and it does not belong in the answer to whether one is needed");
    }

    @Test
    void workThatDoesNotThrowIsNotTalkedAbout() {
        StringBuilder ran = new StringBuilder();

        String said = printed(() -> {
            Supervised.forever("The file watcher", "Restart it.", () -> ran.append("a")).run();
            Supervised.once("Handling Order.class", () -> ran.append("b")).run();
        });

        assertEquals("ab", ran.toString());
        assertEquals("", said.trim());
        assertFalse(ledgerNames("The file watcher"));
    }

    /**
     * Asks whether the ledger names this subject rather than how many entries
     * it holds. The ledger is process-wide and a background scan elsewhere can
     * add to it while this runs: an absolute count made this class fail once in
     * four runs, which is the kind of test that teaches people to re-run rather
     * than to look.
     */
    private static boolean ledgerNames(String subject) {
        return RestartLedger.digest().stream().anyMatch(line -> line.contains(subject));
    }

    private static String printed(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
