/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReloadBuildAutomaticTest {
    @Test void startedAndFailedBuildsHoldSourcesAndResourcesAndCoalesceLatestWork() {
        var f = new ReloadBuildTest.Fixture();
        assertTrue(f.queue.build("owner", "started", () -> {}));
        f.queue.submitAutomatic("Service.java", "source", () -> f.applied.add(1));
        f.queue.submitAutomatic("spring.xml", "resource", () -> f.applied.add(2));
        f.queue.submitAutomatic("Service.java", "latest source", () -> f.applied.add(3));
        f.run();
        assertTrue(f.applied.isEmpty(), "a source or resource escaped BUILD started");
        assertEquals(2, f.queue.control("status", () -> {}).pendingActions());
        assertTrue(f.queue.build("owner", "failed", () -> {}));
        f.queue.submitAutomatic("Service.java", "repaired source", () -> f.applied.add(4));
        f.queue.control("resume", () -> {}); f.run();
        assertTrue(f.applied.isEmpty(), "resume must not release a failed build");
        assertFalse(f.queue.build("other", "ok", () -> {}));
        assertTrue(f.queue.build("owner", "started", () -> {})); f.run();
        assertTrue(f.applied.isEmpty());
        assertTrue(f.queue.build("owner", "ok", () -> {})); f.run();
        assertEquals(List.of(4, 2), f.applied, "latest action per key, original key order");
        assertEquals(0, f.queue.control("status", () -> {}).pendingActions());
        assertEquals("none", f.queue.buildHoldKind());
    }

    @Test void anUnpausedBuildReportsItsHeldAutomaticAction() {
        var f = new ReloadBuildTest.Fixture();
        assertTrue(f.queue.build("owner", "started", () -> {}));
        f.queue.submitAutomatic("Service.java", "source", () -> f.applied.add(1)); f.run();
        var state = f.queue.control("status", () -> {});
        assertFalse(state.paused());
        assertEquals(1, state.pendingActions());
        assertTrue(f.queue.healthLine().contains("Build holding 0 class files and 1 actions since"));
    }

    @Test void successfulBuildDrainsAutomaticOnlyWorkIncludingTheAcceptanceScan() {
        var f = new ReloadBuildTest.Fixture();
        f.build("started");
        f.queue.submitAutomatic("first.java", "source", () -> f.applied.add(1)); f.run();
        f.scan = () -> {
            f.queue.submitAutomatic("first.java", "latest source", () -> f.applied.add(3));
            f.queue.submitAutomatic("second.xml", "resource", () -> f.applied.add(2));
        };
        f.build("ok"); f.run();
        assertEquals(List.of(3, 2), f.applied);
        assertNull(f.queue.healthLine());
    }

    @Test void bothBuildAndPauseMustClearInEitherOrder() {
        for (boolean buildFirst : List.of(false, true)) {
            var f = new ReloadBuildTest.Fixture();
            assertTrue(f.queue.build("owner", "started", () -> {}));
            f.queue.control("pause", () -> {});
            f.queue.submitAutomatic("Service.java", "source", () -> f.applied.add(1));
            f.write("Class", 2); f.run();
            if (buildFirst) assertTrue(f.queue.build("owner", "ok", () -> {}));
            else f.queue.control("resume", () -> {});
            f.run(); assertTrue(f.applied.isEmpty());
            assertEquals(1, f.queue.control("status", () -> {}).pendingActions());
            if (buildFirst) f.queue.control("resume", () -> {});
            else assertTrue(f.queue.build("owner", "ok", () -> {}));
            f.run();
            assertEquals(2, f.applied.size());
            assertTrue(f.applied.containsAll(List.of(1, 2)));
            assertEquals(0, f.queue.control("status", () -> {}).pendingActions());
            assertEquals(0, f.queue.control("status", () -> {}).pendingClasses());
        }
    }

    @Test void supersededSuccessCannotReleaseAutomaticWork() {
        var f = new ReloadBuildTest.Fixture();
        f.build("started");
        f.queue.submitAutomatic("Service.java", "source", () -> f.applied.add(1));
        f.build("ok"); f.build("started"); f.build("failed"); f.run();
        assertTrue(f.applied.isEmpty());
        assertEquals(1, f.queue.control("status", () -> {}).pendingActions());
        f.build("ok"); f.run(); assertEquals(List.of(1), f.applied);
    }

    @Test void aNewHoldAfterClassAdmissionKeepsDeferredActionsPending() {
        var f = new ReloadBuildTest.Fixture();
        f.build("started"); f.write("Class", 1);
        f.queue.submitAutomatic("spring.xml", "resource", () -> f.applied.add(2)); f.run();
        f.duringApply = () -> { f.duringApply = () -> {}; f.build("started"); };
        f.build("ok"); f.run();
        assertEquals(List.of(1), f.applied);
        assertEquals(1, f.queue.control("status", () -> {}).pendingActions());
        f.build("ok"); f.run(); assertEquals(List.of(1, 2), f.applied);
    }

    @Test void scanFailureKeepsAutomaticWorkAndItsOwner() {
        var f = new ReloadBuildTest.Fixture();
        assertTrue(f.queue.build("owner", "started", () -> {}));
        f.queue.submitAutomatic("Service.java", "source", () -> f.applied.add(1)); f.run();
        assertTrue(f.queue.build("owner", "ok", () -> { throw new IllegalStateException("scan failed"); })); f.run();
        assertTrue(f.applied.isEmpty());
        assertEquals("named", f.queue.buildHoldKind());
        assertFalse(f.queue.build("other", "started", () -> {}));
        assertTrue(f.queue.build("owner", "ok", () -> {})); f.run();
        assertEquals(List.of(1), f.applied);
    }

    @Test void unreadableClassOutputDoesNotReleaseAutomaticWorkEither() {
        var f = new ReloadBuildTest.Fixture();
        f.build("started"); f.write("Class", 2); f.disk.remove(f.path("Class"));
        f.queue.submitAutomatic("Service.java", "source", () -> f.applied.add(1));
        f.build("ok"); f.run(); assertTrue(f.applied.isEmpty());
        assertEquals(1, f.queue.control("status", () -> {}).pendingActions());
        f.disk.put(f.path("Class"), new byte[]{2}); f.build("ok"); f.run();
        assertEquals(List.of(2, 1), f.applied);
    }

    @Test void oneFailingDeferredCallbackDoesNotStrandTheNext() {
        var f = new ReloadBuildTest.Fixture();
        f.build("started");
        f.queue.submitAutomatic("first.xml", "failing", () -> { throw new IllegalStateException("callback failed"); });
        f.queue.submitAutomatic("second.xml", "resource", () -> f.applied.add(2)); f.run();
        assertTrue(f.applied.isEmpty());
        f.build("ok"); f.run(); assertEquals(List.of(2), f.applied);
        assertEquals(0, f.queue.control("status", () -> {}).pendingActions());
    }

    @Test void anAdmittedClassCallbackFailureDoesNotStrandAutomaticWorkAfterRelease() {
        var f = new ReloadBuildTest.Fixture();
        f.build("started"); f.write("Class", 1);
        f.queue.submitAutomatic("spring.xml", "resource", () -> f.applied.add(2)); f.run();
        f.duringApply = () -> { throw new IllegalStateException("class callback failed"); };
        f.build("ok"); f.run();
        assertEquals(List.of(1, 2), f.applied);
        assertEquals("none", f.queue.buildHoldKind());
        assertEquals(0, f.queue.control("status", () -> {}).pendingActions());
    }
}
