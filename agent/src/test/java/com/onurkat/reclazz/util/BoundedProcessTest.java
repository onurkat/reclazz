/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The timeout has to be real when the process keeps its output open, which
 * is what a hung process does.
 */
@DisabledOnOs(OS.WINDOWS)
class BoundedProcessTest {

    @Test
    void aProcessThatHangsWithItsOutputOpenIsStoppedAtTheTimeout() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        long t0 = System.nanoTime();
        BoundedProcess.Result result = BoundedProcess.run(
                new ProcessBuilder("/bin/sh", "-c", "echo started; sleep 60"),
                Duration.ofSeconds(1), seen::add, 4000);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(result.timedOut(), "must report the timeout");
        assertTrue(elapsedMs < 15_000, "came back at the timeout, not at the process's leisure: " + elapsedMs + "ms");
        assertEquals(List.of("started"), seen, "what it printed before hanging was seen");
        assertTrue(result.tail().contains("started"), result.tail());
    }

    @Test
    void aProcessThatFinishesReportsItsExitCodeAndTail() throws Exception {
        BoundedProcess.Result result = BoundedProcess.run(
                new ProcessBuilder("/bin/sh", "-c", "echo one; echo two 1>&2; exit 3"),
                Duration.ofSeconds(30), line -> { }, 4000);
        assertFalse(result.timedOut());
        assertEquals(3, result.exitCode());
        assertTrue(result.tail().contains("one") && result.tail().contains("two"),
                "stdout and stderr both, in the tail: " + result.tail());
    }

    @Test
    void theTailIsBounded() throws Exception {
        BoundedProcess.Result result = BoundedProcess.run(
                new ProcessBuilder("/bin/sh", "-c", "i=0; while [ $i -lt 500 ]; do echo line$i; i=$((i+1)); done"),
                Duration.ofSeconds(30), line -> { }, 100);
        assertTrue(result.tail().length() <= 100, "tail length " + result.tail().length());
        assertTrue(result.tail().contains("line499"), "the end of the output is what is kept: " + result.tail());
    }
}
