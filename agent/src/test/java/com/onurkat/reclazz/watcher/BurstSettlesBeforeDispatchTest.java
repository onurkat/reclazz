/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A save is the files that land together. Judged per file, two class files
 * written 30ms apart by one javac run came due in different passes of the
 * poll loop and reached the reload thread as two saves; judged from the
 * newest pending file, the first waits for the burst to settle and a single
 * save waits exactly what it did.
 */
class BurstSettlesBeforeDispatchTest {

    private static final long DEBOUNCE = 200;

    private static FileWatcher.PendingEvent at(long ts, String name) {
        return new FileWatcher.PendingEvent(ts, Path.of("/w/" + name + ".class"),
                ChangeEvent.Type.MODIFIED, "m", null);
    }

    @Test
    void aSingleSaveIsDispatchedAfterItsOwnDebounceAndNotBefore() {
        var one = List.of(at(1000, "A"));
        assertTrue(FileWatcher.dueNow(one, 1199, DEBOUNCE).isEmpty());
        assertEquals(1, FileWatcher.dueNow(one, 1200, DEBOUNCE).size());
    }

    @Test
    void theFirstFileOfABurstWaitsForTheBurstToSettle() {
        var two = List.of(at(1000, "Caller"), at(1030, "Target"));
        assertTrue(FileWatcher.dueNow(two, 1200, DEBOUNCE).isEmpty(),
                "at 1200 the first file is due on its own, and used to go alone");
        assertTrue(FileWatcher.dueNow(two, 1229, DEBOUNCE).isEmpty());
        assertEquals(2, FileWatcher.dueNow(two, 1230, DEBOUNCE).size(), "both, once the newest is due");
    }

    @Test
    void aBuildThatKeepsWritingStillGetsItsDueFilesAfterTheHold() {
        // Files keep arriving every 100ms; nothing is ever quiet for 200ms.
        var stream = new java.util.ArrayList<FileWatcher.PendingEvent>();
        for (int i = 0; i < 12; i++) stream.add(at(1000 + i * 100, "C" + i));
        long now = 1000 + 11 * 100 + 50;                       // 50ms after the newest
        List<FileWatcher.PendingEvent> due = FileWatcher.dueNow(stream, now, DEBOUNCE);
        assertFalse(due.isEmpty(), "the oldest has waited past the hold, so the due ones go");
        for (FileWatcher.PendingEvent p : due) {
            assertTrue(now - p.timestamp() >= DEBOUNCE, "only files past their own debounce: " + p.path());
        }
        assertTrue(due.size() < stream.size(), "the newest, still inside its debounce, waits");
    }

    @Test
    void nothingPendingIsNothingDue() {
        assertTrue(FileWatcher.dueNow(List.of(), 5000, DEBOUNCE).isEmpty());
    }
}
