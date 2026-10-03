/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReloadPauseTest {
    @Test void queuedChangesRemainDiscoverableAndResumeUsesLatestBytes() {
        var f = new ReloadBuildTest.Fixture();
        f.write("A", 1);
        f.queue.control("pause", () -> {});
        f.queue.control("pause", () -> {});
        f.write("A", 2); f.run();
        assertTrue(f.applied.isEmpty());
        assertEquals(1, f.queue.control("status", () -> {}).pendingClasses());
        assertTrue(f.queue.healthLine().contains("paused"));
        f.disk.put(f.path("A"), new byte[]{3});
        f.queue.control("resume", () -> {}); f.run();
        f.queue.control("resume", () -> {}); f.run();
        assertEquals(List.of(3), f.applied);
        assertNull(f.queue.healthLine());
    }

    @Test void pauseDuringCaptureRestoresPendingEvenIfResumedImmediately() {
        for (boolean build : List.of(false, true)) for (boolean resume : List.of(false, true)) {
            var f = new ReloadBuildTest.Fixture();
            if (build) assertTrue(f.queue.build("alice", "started", () -> {}));
            f.write("A", 1);
            f.duringRead = () -> {
                f.duringRead = () -> {};
                f.queue.control("pause", () -> {});
                f.write("A", 2);
                if (resume) f.queue.control("resume", () -> {});
            };
            if (build) assertTrue(f.queue.build("alice", "ok", () -> {}));
            f.run();
            if (!resume) {
                assertTrue(f.applied.isEmpty());
                assertEquals(1, f.queue.control("status", () -> {}).pendingClasses());
                f.queue.control("resume", () -> {}); f.run();
            }
            assertEquals(List.of(2), f.applied);
            assertEquals("none", f.queue.buildHoldKind());
        }
    }

    @Test void pauseDoesNotCancelAnAlreadyAdmittedBatch() {
        var f = new ReloadBuildTest.Fixture();
        f.write("A", 1); f.write("B", 1);
        f.duringApply = () -> { f.queue.control("pause", () -> {}); f.write("C", 2); };
        f.run();
        assertEquals(List.of(1, 1), f.applied);
        assertEquals(1, f.queue.control("status", () -> {}).pendingClasses());
    }

    @Test void resumeDoesNotReleaseActiveOrFailedOwnedBuilds() {
        for (String state : List.of("started", "failed")) {
            var f = new ReloadBuildTest.Fixture();
            assertTrue(f.queue.build("alice", "started", () -> {}));
            assertTrue(f.queue.build("alice", state, () -> {}));
            f.queue.control("pause", () -> {}); f.write("A", 2);
            assertEquals("named", f.queue.control("resume", () -> {}).buildHold()); f.run();
            assertTrue(f.applied.isEmpty());
            assertFalse(f.queue.build("bob", "ok", () -> {}));
            assertTrue(f.queue.build("alice", "ok", () -> {})); f.run();
            assertEquals(List.of(2), f.applied);
        }
    }

    @Test void successfulBuildDoesNotResumeManualPauseAndCaptureFailureKeepsOwnership() {
        var f = new ReloadBuildTest.Fixture();
        f.queue.control("pause", () -> {});
        assertTrue(f.queue.build("alice", "started", () -> {})); f.write("A", 2);
        f.disk.remove(f.path("A"));
        assertTrue(f.queue.build("alice", "ok", () -> {})); f.run();
        assertEquals("named", f.queue.buildHoldKind());
        f.disk.put(f.path("A"), new byte[]{2});
        assertTrue(f.queue.build("alice", "ok", () -> {})); f.run();
        assertTrue(f.applied.isEmpty());
        assertTrue(f.queue.control("status", () -> {}).paused());
        assertEquals("none", f.queue.buildHoldKind());
        f.queue.control("resume", () -> {}); f.run();
        assertEquals(List.of(2), f.applied);
    }

    @Test void nonClassWorkIsCoalescedAndAlsoGatedAtExecution() {
        var f = new ReloadBuildTest.Fixture();
        f.queue.submitAutomatic("file", "resource", () -> f.applied.add(1));
        f.queue.control("pause", () -> {});
        f.queue.submitAutomatic("file", "resource", () -> f.applied.add(2));
        f.run();
        assertEquals(1, f.queue.control("status", () -> {}).pendingActions());
        assertTrue(f.applied.isEmpty());
        f.queue.control("resume", () -> {}); f.run();
        assertEquals(List.of(2), f.applied);
        assertEquals(0, f.queue.control("status", () -> {}).pendingActions());
    }
    @Test void oneFailedDeferredActionDoesNotStrandOthers() {
        var f = new ReloadBuildTest.Fixture();
        f.queue.control("pause", () -> {});
        f.queue.submitAutomatic("first", "failing", () -> { throw new IllegalStateException("fixture"); });
        f.queue.submitAutomatic("second", "working", () -> f.applied.add(2));
        f.run();
        f.queue.control("resume", () -> {}); f.run();
        assertEquals(List.of(2), f.applied);
    }

}
