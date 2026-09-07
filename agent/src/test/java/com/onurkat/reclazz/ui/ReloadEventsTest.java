/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a recording of a session holds: one event per reload with the class,
 * the kind, the duration and the shape, and one per failure with the reason,
 * read back the way jfr print and Mission Control read them.
 */
class ReloadEventsTest {

    @TempDir
    Path tmp;

    @Test
    void reloadsAndFailuresAreRecordedWithTheirFields() throws Exception {
        Path file = tmp.resolve("session.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("reclazz.Reload");
            recording.enable("reclazz.ReloadFailed");
            recording.start();

            StatusReporter.reload("demo.OrderService", 12);
            StatusReporter.structuralReload("demo.Cart", 30, "v2, +1 method");
            StatusReporter.reload("demo.Batched", -1);
            ReloadEvents.failed("demo.Broken", "changed its superclass");

            recording.stop();
            recording.dump(file);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getEventType().getName().startsWith("reclazz."))
                .toList();
        assertEquals(4, events.size(), () -> "one event per reload and per failure: " + events);

        RecordedEvent body = byClass(events, "demo.OrderService");
        assertEquals("reclazz.Reload", body.getEventType().getName());
        assertFalse(body.getBoolean("structural"));
        assertEquals(12, body.getDuration("measured").toMillis());

        RecordedEvent structural = byClass(events, "demo.Cart");
        assertTrue(structural.getBoolean("structural"));
        assertEquals(30, structural.getDuration("measured").toMillis());
        assertEquals("v2, +1 method", structural.getString("shape"));

        RecordedEvent batched = byClass(events, "demo.Batched");
        assertEquals(-1, batched.getDuration("measured").toMillis(), "unmeasured stays -1 rather than pretending");

        RecordedEvent failed = byClass(events, "demo.Broken");
        assertEquals("reclazz.ReloadFailed", failed.getEventType().getName());
        assertEquals("changed its superclass", failed.getString("reason"));
        assertEquals("Reclazz", failed.getEventType().getCategoryNames().get(0), "grouped under Reclazz in Mission Control");
    }

    @Test
    void withoutARecordingNothingIsThrownOrKept() {
        assertDoesNotThrow(() -> {
            StatusReporter.reload("demo.Quiet", 3);
            ReloadEvents.failed("demo.Quiet", "nothing");
        });
    }

    private static RecordedEvent byClass(List<RecordedEvent> events, String className) {
        return events.stream().filter(e -> className.equals(e.getString("className"))).findFirst()
                .orElseThrow(() -> new AssertionError("no event for " + className + " in " + events));
    }
}
