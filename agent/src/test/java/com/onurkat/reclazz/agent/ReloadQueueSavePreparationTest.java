/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReloadQueueSavePreparationTest {
    private static ChangeEvent event(String name) {
        return new ChangeEvent(Path.of("/synthetic/" + name + ".class"), ChangeEvent.Type.MODIFIED, "test", "classes").withBytes(new byte[]{1});
    }
    private static ReloadQueue queue(List<String> log, ReloadQueue.SavePreparer preparer) {
        return new ReloadQueue(e -> {
            if (e.getPath().getFileName().toString().equals("Outer.class")) assertArrayEquals(new byte[]{2}, e.getBytes());
            log.add(e.getPath().getFileName().toString());
        }, new ReloadQueue.Bracket() {
            public void begin() { log.add("begin"); } public void end() { log.add("end"); }
        }, Runnable::run, new ReloadStall(() -> 1000L, ReloadQueue.STALL_WARN_MS), () -> 1000L,
                millis -> { }, path -> new byte[]{1}, preparer);
    }
    @Test void expandedSaveIsBracketedAndUsesPreparedBytesThenClearsItsScope() {
        List<String> log = new ArrayList<>(); ChangeEvent outer = event("Outer"), holder = event("Outer$1");
        ReloadQueue queue = queue(log, new ReloadQueue.SavePreparer() {
            public ReloadQueue.SavePlan prepare(List<ChangeEvent> captured) {
                assertArrayEquals(new byte[]{1}, captured.get(0).getBytes());
                assertThrows(UnsupportedOperationException.class, () -> captured.add(holder));
                log.add("prepare"); return new ReloadQueue.SavePlan(List.of(outer.withBytes(new byte[]{2}), holder), Map.of());
            }
            public void complete() { log.add("clear"); }
        });
        queue.enqueueClassFile(outer); queue.submitClassBatch(0);
        assertEquals("prepare", log.get(0)); assertEquals("begin", log.get(1));
        assertEquals(Set.of("Outer.class", "Outer$1.class"), new HashSet<>(log.subList(2, 4)));
        assertEquals(List.of("end", "clear"), log.subList(4, 6));
        assertArrayEquals(new byte[]{1}, outer.getBytes());
    }
    @Test void refusedPairNeverReachesTheHandlerOrBracketAndStillClears() {
        List<String> log = new ArrayList<>(); ChangeEvent outer = event("Outer");
        ReloadQueue queue = queue(log, new ReloadQueue.SavePreparer() {
            public ReloadQueue.SavePlan prepare(List<ChangeEvent> captured) { return new ReloadQueue.SavePlan(List.of(), Map.of(outer, "unsupported initializer")); }
            public void complete() { log.add("clear"); }
        });
        queue.enqueueClassFile(outer); queue.submitClassBatch(0); assertEquals(List.of("clear"), log);
    }
}
