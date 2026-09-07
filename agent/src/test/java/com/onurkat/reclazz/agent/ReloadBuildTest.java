/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ReloadBuildTest {
    static class Fixture {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final Map<Path, byte[]> disk = new HashMap<>();
        final List<Integer> applied = new ArrayList<>();
        final AtomicLong now = new AtomicLong(1000);
        Runnable duringRead = () -> {};
        Runnable duringApply = () -> {};
        Runnable scan = () -> {};
        int brackets;
        final ReloadQueue queue = new ReloadQueue(event -> {
            applied.add((int) event.getBytes()[0]);
            duringApply.run();
        }, new ReloadQueue.Bracket() {
            public void begin() { brackets++; }
            public void end() {}
        }, tasks::add, new ReloadStall(now::get, 30_000), now::get, now::addAndGet, path -> {
            byte[] bytes = disk.get(path);
            duringRead.run();
            if (bytes == null) throw new IOException("missing " + path);
            return bytes;
        });
        Path path(String name) { return Path.of("/output/" + name + ".class"); }
        void write(String name, int value) {
            disk.put(path(name), new byte[]{(byte) value});
            queue.enqueueClassFile(new ChangeEvent(path(name), ChangeEvent.Type.MODIFIED, "m", "classes"));
            queue.submitClassBatch(1);
        }
        void build(String state) { queue.build(state, () -> scan.run()); }
        void run() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    @Test void staleSuccessQueuedBeforeANewerFailedBuildDoesNotPublish() {
        Fixture f = new Fixture();
        f.build("started"); f.write("A", 1); f.build("ok");
        f.build("started"); f.write("A", 2); f.build("failed");
        f.run();
        assertTrue(f.applied.isEmpty());
        assertTrue(f.queue.healthLine().contains("holding 1"));
        f.build("started"); f.write("A", 3); f.build("ok"); f.run();
        assertEquals(List.of(3), f.applied);
        assertNull(f.queue.healthLine());
    }

    @Test void aQueuedOrdinaryTaskCannotRunThroughAHold() {
        Fixture f = new Fixture();
        f.write("A", 1); f.build("started"); f.write("A", 2); f.run();
        assertTrue(f.applied.isEmpty());
        f.build("ok"); f.run();
        assertEquals(List.of(2), f.applied);
    }

    @Test void buildStartingDuringCaptureInvalidatesTheWholeCapture() {
        for (boolean held : List.of(false, true)) {
            Fixture f = new Fixture();
            if (held) f.build("started");
            f.write("A", 1); f.write("B", 1);
            f.duringRead = () -> {
                f.duringRead = () -> {};
                f.build("started"); f.write("A", 2); f.write("B", 2); f.build("failed");
            };
            if (held) f.build("ok");
            f.run();
            assertTrue(f.applied.isEmpty(), "capture crossed a new build, held=" + held);
            f.build("ok"); f.run();
            assertEquals(List.of(2, 2), f.applied);
            assertEquals(1, f.brackets);
        }
    }

    @Test void aBuildStartingDuringScanInvalidatesAcceptance() {
        Fixture f = new Fixture();
        f.build("started"); f.write("A", 1);
        f.scan = () -> { f.build("started"); f.write("A", 2); f.build("failed"); };
        f.build("ok"); f.run();
        assertTrue(f.applied.isEmpty());
        assertNotNull(f.queue.healthLine());
    }

    @Test void anUnreadableMemberDiscardsTheWholeCapture() {
        Fixture f = new Fixture();
        f.build("started"); f.write("A", 1); f.write("B", 2);
        f.disk.remove(f.path("B"));
        f.build("ok"); f.run();
        assertTrue(f.applied.isEmpty());
        assertTrue(f.queue.healthLine().contains("holding 2"));
        f.disk.put(f.path("B"), new byte[]{2});
        f.build("ok"); f.run();
        assertEquals(List.of(1, 2), f.applied);
    }

    @Test void acceptedBatchFinishesOnItsOwnBytes() {
        Fixture f = new Fixture();
        f.build("started"); f.write("A", 1); f.write("B", 1);
        f.duringApply = () -> {
            f.duringApply = () -> {};
            f.build("started"); f.write("A", 2); f.write("B", 2); f.build("failed");
        };
        f.build("ok"); f.run();
        assertEquals(List.of(1, 1), f.applied);
        assertTrue(f.queue.healthLine().contains("holding 2"));
    }

    @Test void successIncludesFilesDiscoveredByTheScan() {
        Fixture f = new Fixture();
        f.build("started"); f.write("A", 1);
        f.scan = () -> f.write("B", 2);
        f.build("ok"); f.run();
        assertEquals(List.of(1, 2), f.applied);
        assertEquals(1, f.brackets);
    }

    @Test void fiveMinutesAndALateFailureNeverReleaseOutput() {
        Fixture f = new Fixture();
        f.build("started"); f.write("A", 1);
        var warnings = new java.util.concurrent.atomic.AtomicInteger();
        com.onurkat.reclazz.ui.StatusReporter.StatusListener listener = (level, message) -> {
            if (message.startsWith("Build result missing")) warnings.incrementAndGet();
        };
        com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
        try {
            f.now.addAndGet(600_000); f.queue.checkBuildHold(); f.queue.checkBuildHold();
            assertEquals(1, warnings.get(), "an abandoned build warns once and stays held");
        } finally {
            com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);
        }
        f.run(); assertTrue(f.applied.isEmpty());
        f.build("failed"); f.run(); assertTrue(f.applied.isEmpty());
        f.build("ok"); f.run(); assertEquals(List.of(1), f.applied);
    }
}
