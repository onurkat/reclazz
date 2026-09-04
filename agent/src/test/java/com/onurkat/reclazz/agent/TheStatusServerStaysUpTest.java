/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.util.Supervised;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The socket the IDE lives on, over a working day.
 *
 * <p>Two of its parts are the kind that stop once and stay stopped, and both
 * look healthy from outside while they do it.
 *
 * <p>The heartbeat is scheduled with {@code scheduleAtFixedRate}, which cancels
 * every future execution the first time the task throws, and says nothing. The
 * IDE then stops hearing from an agent that is perfectly fine, decides it is
 * gone, and drops a connection it did not need to drop. The first test here is
 * that behaviour on a real scheduler, because it is the part nobody believes
 * until they see it.
 *
 * <p>The accept loop is the worse one. It catches the socket exceptions inside
 * its {@code while}, so it survives those, and a runtime exception from
 * anywhere else ends the thread while the {@code ServerSocket} stays bound. The
 * port file still points at a port that is still open and that nobody is
 * accepting on, so the IDE's reconnect finds something to connect to, forever,
 * and never gets in.
 */
class TheStatusServerStaysUpTest {

    /** What {@code scheduleAtFixedRate} does with a task that throws, once. */
    @Test
    void aScheduledTaskThatThrowsIsNeverRunAgain() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger beats = new AtomicInteger();
        try {
            scheduler.scheduleAtFixedRate(() -> {
                if (beats.incrementAndGet() == 2) {
                    throw new IllegalStateException("one bad beat");
                }
            }, 0, 10, TimeUnit.MILLISECONDS);

            Thread.sleep(300);

            assertEquals(2, beats.get(),
                    "the schedule is cancelled by the throw, silently, and this is the whole "
                            + "reason the heartbeat had to be wrapped");
        } finally {
            scheduler.shutdownNow();
        }
    }

    /** And what wrapping it buys: the beat after the bad one. */
    @Test
    void aWrappedScheduledTaskKeepsItsSchedule() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger beats = new AtomicInteger();
        CountDownLatch enough = new CountDownLatch(5);
        try {
            scheduler.scheduleAtFixedRate(Supervised.once("The heartbeat", () -> {
                int n = beats.incrementAndGet();
                enough.countDown();
                if (n == 2) throw new IllegalStateException("one bad beat");
            }), 0, 10, TimeUnit.MILLISECONDS);

            assertTrue(enough.await(5, TimeUnit.SECONDS),
                    "the heartbeat stopped at beat " + beats.get()
                            + "; one failure must cost one beat, not the session");
        } finally {
            scheduler.shutdownNow();
        }
    }

    /**
     * The accept loop's own protection, as a shape rather than through a
     * socket: a body that throws must cost the one connection it was handling.
     */
    @Test
    void aConnectionThatFailsDoesNotEndTheLoop() {
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            int attempt = i;
            Supervised.once("Accepting a status client", () -> {
                if (attempt == 1) throw new IllegalStateException("client went away mid-handshake");
                accepted.incrementAndGet();
            }).run();
        }

        assertEquals(4, accepted.get(),
                "four of the five connections were fine and all four have to be served");
    }
}
