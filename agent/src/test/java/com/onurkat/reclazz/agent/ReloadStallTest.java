/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A reload that never comes back holds every save after it, silently. This
 * is the sentence that breaks the silence: once per reload, with where the
 * thread is, and once more when the reload finishes.
 */
class ReloadStallTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final StatusReporter.StatusListener listener = (level, message) -> lines.add(level + " " + message);

    @BeforeEach
    void listen() {
        StatusReporter.addListener(listener);
    }

    @AfterEach
    void stopListening() {
        StatusReporter.removeListener(listener);
    }

    @Test
    void aLongReloadIsReportedOnceAndItsEndIsReportedToo() {
        ReloadStall stall = new ReloadStall(now::get, 30_000);
        stall.begin("Handling OrderService.class");

        now.addAndGet(29_000);
        stall.check();
        assertTrue(lines.isEmpty(), "nothing to say before the threshold");
        assertNull(stall.healthLine());

        now.addAndGet(2_000);
        stall.check();
        assertEquals(1, lines.size(), lines.toString());
        String warning = lines.get(0);
        assertTrue(warning.startsWith("WARN"), warning);
        assertTrue(warning.contains("Handling OrderService.class has been running for 31s"), warning);
        assertTrue(warning.contains("waiting behind it"), warning);
        assertTrue(warning.contains("the reload thread is in "), "where, not only that: " + warning);
        assertTrue(warning.contains(getClass().getSimpleName()), "the thread that began it is this test's: " + warning);

        now.addAndGet(60_000);
        stall.check();
        stall.check();
        assertEquals(1, lines.size(), "said once, however long it goes on: " + lines);
        assertNotNull(stall.healthLine());
        assertTrue(stall.healthLine().contains("91s so far"), stall.healthLine());

        stall.end();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(1).contains("finished after 91s"), lines.get(1));
        assertNull(stall.healthLine());
    }

    @Test
    void aReloadThatFinishesInTimeSaysNothing() {
        ReloadStall stall = new ReloadStall(now::get, 30_000);
        stall.timed("Handling Quick.class", () -> now.addAndGet(5_000)).run();
        stall.check();
        assertTrue(lines.isEmpty(), lines.toString());
    }

    @Test
    void theNextReloadStartsWithACleanSlate() {
        ReloadStall stall = new ReloadStall(now::get, 30_000);
        stall.begin("first");
        now.addAndGet(40_000);
        stall.check();
        stall.end();
        lines.clear();

        stall.begin("second");
        now.addAndGet(10_000);
        stall.check();
        assertTrue(lines.isEmpty(), "the first reload's overrun must not be charged to the second: " + lines);
        now.addAndGet(25_000);
        stall.check();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("second has been running for 35s"), lines.get(0));
    }

    @Test
    void timedBracketsTheWorkEvenWhenItThrows() {
        ReloadStall stall = new ReloadStall(now::get, 30_000);
        assertThrows(IllegalStateException.class,
                () -> stall.timed("boom", () -> { throw new IllegalStateException("x"); }).run());
        now.addAndGet(60_000);
        stall.check();
        assertTrue(lines.isEmpty(), "a reload that ended by throwing is not still running: " + lines);
    }
}
