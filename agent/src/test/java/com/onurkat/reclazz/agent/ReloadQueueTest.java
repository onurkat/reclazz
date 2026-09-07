/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the queue does with class files, without a thread, a clock or a
 * sleep of its own: the executor runs inline, time is a number, and the
 * grace wait is a hook a test can use to let stragglers in.
 */
class ReloadQueueTest {

    private final List<String> log = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000);
    private Runnable duringGrace = () -> { };

    private ReloadQueue queue() {
        ReloadQueue.Handler handler = e -> log.add("handle " + e.getPath().getFileName());
        ReloadQueue.Bracket bracket = new ReloadQueue.Bracket() {
            @Override public void begin() { log.add("begin"); }
            @Override public void end() { log.add("end"); }
        };
        ReloadQueue.Sleeper sleeper = ms -> {
            now.addAndGet(ms);
            duringGrace.run();
        };
        return new ReloadQueue(handler, bracket, Runnable::run,
                new ReloadStall(now::get, ReloadQueue.STALL_WARN_MS), now::get, sleeper);
    }

    private static ChangeEvent classFile(String name) {
        return new ChangeEvent(Path.of("/w/" + name + ".class"), ChangeEvent.Type.MODIFIED, "m", "classes");
    }

    @Test
    void oneClassFileGoesStraightThroughWithNoBracket() {
        ReloadQueue queue = queue();
        queue.enqueueClassFile(classFile("Only"));
        queue.submitClassBatch(1);
        assertEquals(List.of("handle Only.class"), log);
    }

    @Test
    void severalClassFilesAreOneBracketInArrivalOrderWhenUnrelated() {
        ReloadQueue queue = queue();
        queue.enqueueClassFile(classFile("B"));
        queue.enqueueClassFile(classFile("A"));
        queue.enqueueClassFile(classFile("C"));
        queue.submitClassBatch(3);
        assertEquals(List.of("begin", "handle B.class", "handle A.class", "handle C.class", "end"), log);
    }

    @Test
    void aStragglerArrivingDuringTheGraceJoinsTheBatch() {
        ReloadQueue queue = queue();
        queue.enqueueClassFile(classFile("First"));
        queue.enqueueClassFile(classFile("Second"));
        duringGrace = () -> {
            queue.enqueueClassFile(classFile("Late"));
            duringGrace = () -> { };
        };
        queue.submitClassBatch(2);
        assertEquals(List.of("begin", "handle First.class", "handle Second.class", "handle Late.class", "end"), log);
    }

    @Test
    void theBracketClosesEvenWhenAHandlerThrows() {
        List<String> seen = new ArrayList<>();
        ReloadQueue.Handler failing = e -> {
            seen.add(e.getPath().getFileName().toString());
            throw new IllegalStateException("boom");
        };
        ReloadQueue.Bracket bracket = new ReloadQueue.Bracket() {
            @Override public void begin() { seen.add("begin"); }
            @Override public void end() { seen.add("end"); }
        };
        ReloadQueue queue = new ReloadQueue(failing, bracket, Runnable::run,
                new ReloadStall(now::get, ReloadQueue.STALL_WARN_MS), now::get, ms -> { });
        queue.enqueueClassFile(classFile("X"));
        queue.enqueueClassFile(classFile("Y"));
        queue.submitClassBatch(2);      // supervised: the throw is reported, not rethrown
        assertEquals(List.of("begin", "X.class", "end"), seen, "the bracket still closes");
    }

    @Test
    void aSecondDrainFindsNothingLeft() {
        ReloadQueue queue = queue();
        queue.enqueueClassFile(classFile("One"));
        queue.submitClassBatch(1);
        queue.submitClassBatch(1);
        assertEquals(List.of("handle One.class"), log, "the second task finds the queue drained");
    }
}
