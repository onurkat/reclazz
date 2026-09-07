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
 * the kind, the duration, the shape and the file, and one per failure with the
 * reason, read back the way jfr print and Mission Control read them. The
 * class name is the JVM's, {@code Outer$1} included: the console's
 * "(inner class)" form names every inner class of Outer alike.
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

            ReloadEvents.reloaded("demo.OrderService", false, 12, null, "/build/classes/demo/OrderService.class");
            ReloadEvents.reloaded("demo.Cart", true, 30, "v2, +1 method", "/src/demo/Cart.java");
            ReloadEvents.reloaded("demo.Batched$1", false, -1, null, null);
            ReloadEvents.failed("demo.Broken", "changed its superclass", "/build/classes/demo/Broken.class");

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
        assertEquals("/build/classes/demo/OrderService.class", body.getString("source"),
                "the file the bytes came from is what a reload is traced back to");

        RecordedEvent structural = byClass(events, "demo.Cart");
        assertTrue(structural.getBoolean("structural"));
        assertEquals(30, structural.getDuration("measured").toMillis());
        assertEquals("v2, +1 method", structural.getString("shape"));
        assertEquals("/src/demo/Cart.java", structural.getString("source"));

        RecordedEvent batched = byClass(events, "demo.Batched$1");
        assertEquals(-1, batched.getDuration("measured").toMillis(), "unmeasured stays -1 rather than pretending");
        assertEquals("", batched.getString("source"), "unknown is empty, not the word null");

        RecordedEvent failed = byClass(events, "demo.Broken");
        assertEquals("reclazz.ReloadFailed", failed.getEventType().getName());
        assertEquals("changed its superclass", failed.getString("reason"));
        assertEquals("/build/classes/demo/Broken.class", failed.getString("source"));
        assertEquals("Reclazz", failed.getEventType().getCategoryNames().get(0), "grouped under Reclazz in Mission Control");
    }

    @Test
    void withoutARecordingNothingIsThrownOrKept() {
        assertDoesNotThrow(() -> {
            ReloadEvents.reloaded("demo.Quiet", false, 3, null, null);
            ReloadEvents.failed("demo.Quiet", "nothing", null);
        });
    }

    private static RecordedEvent byClass(List<RecordedEvent> events, String className) {
        return events.stream().filter(e -> className.equals(e.getString("className"))).findFirst()
                .orElseThrow(() -> new AssertionError("no event for " + className + " in " + events));
    }
}
