/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import com.onurkat.reclazz.config.AgentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The session's record: every line, timestamped, level named, no colour, appended. */
class SessionLogTest {

    @TempDir
    Path tmp;

    private SessionLog log;

    @AfterEach
    void stop() {
        if (log != null) StatusReporter.removeListener(log);
    }

    @Test
    void everyStatusLineIsAppendedWithTimeAndLevel() throws Exception {
        Path file = tmp.resolve("logs").resolve("reclazz-session.log");
        log = SessionLog.open(file, "9.9.9");
        assertNotNull(log);
        StatusReporter.addListener(log);

        StatusReporter.info("Class file changed: Order.class");
        StatusReporter.reload("demo.Order", 12);
        StatusReporter.warn("first line\nsecond line");

        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).matches("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z SESSION Reclazz 9\\.9\\.9 started in JVM \\d+.*"), lines.get(0));
        assertTrue(lines.get(1).matches("\\S+ INFO Class file changed: Order\\.class"), lines.get(1));
        assertTrue(lines.get(2).matches("\\S+ RELOAD Reloaded demo\\.Order \\(12ms\\)"), lines.get(2));
        assertEquals("first line second line", lines.get(3).replaceFirst("^\\S+ WARN ", ""), "one record per line, whatever the message");
        for (String line : lines) assertFalse(line.contains("\u001B"), "no colour codes in the record: " + line);
    }

    @Test
    void aSecondSessionAppendsRatherThanReplaces() throws Exception {
        Path file = tmp.resolve("session.log");
        SessionLog first = SessionLog.open(file, "1");
        first.onEvent("INFO", "from the first run");
        SessionLog second = SessionLog.open(file, "2");
        second.onEvent("INFO", "from the second run");
        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(1).endsWith("from the first run"));
        assertTrue(lines.get(3).endsWith("from the second run"));
    }

    @Test
    void anUnwritableFileCostsTheRecordNotTheSession() throws Exception {
        Path notADirectory = tmp.resolve("file");
        Files.writeString(notADirectory, "x");
        assertNull(SessionLog.open(notADirectory.resolve("under-a-file.log"), "1"),
                "the failure is said and the listener is not installed");
    }

    @Test
    void theArgumentIsAnOrdinaryOne() {
        assertEquals(Path.of("/tmp/r.log"), AgentConfig.parse("sessionLog=/tmp/r.log,verbose=true").getSessionLog());
        assertNull(AgentConfig.parse("verbose=true").getSessionLog());
    }
}
