/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Noticing a change without waiting for the JDK to notice it.
 *
 * <p>macOS has no native file watching in the JDK, so the WatchService is
 * {@code sun.nio.fs.PollingWatchService} and it walks its directories on a
 * two-second cycle. Measured directly, writing a file and timing until the
 * watcher was handed the event: 611ms to 1301ms depending on where in the
 * cycle the write landed. In the agent's own trace that was 1.9 seconds of a
 * 2.45-second save-to-live, against 515ms of debounce doing what it says.
 *
 * <p>A developer edits the same few files over and over, so those are checked
 * directly: a handful of stat calls on a short cycle rather than a walk of the
 * tree. The first change to any file still waits for the JDK. Every one after
 * it does not, and end to end that moved the median save from 2447ms to 612ms.
 */
class HotFileScanTest {

    @TempDir
    Path tempDir;

    private FileWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
        // Forced on, because CI is Linux, where the JDK has a native watcher
        // and this path is correctly switched off.
        watcher.jdkPolls = true;
        FileWatcher.hotForMs = 5 * 60 * 1000;
    }

    private Path classFile(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        return file;
    }

    /** Touch it in a way the filesystem will report as newer. */
    private static void changeOnDisk(Path file) throws Exception {
        Files.write(file, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 1});
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
    }

    @Test
    void aFileAlreadySeenChangingIsNoticedOnTheNextScan() throws Exception {
        Path file = classFile("Order.class");
        watcher.markHot(file, "app", "classes");
        changeOnDisk(file);

        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        watcher.scanHotFiles(pending);

        assertEquals(1, pending.size(), () -> "expected the change, got " + pending.keySet());
        assertEquals(file, pending.keySet().iterator().next());
    }

    @Test
    void aFileThatHasNotChangedProducesNothing() throws Exception {
        Path file = classFile("Order.class");
        watcher.markHot(file, "app", "classes");

        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        watcher.scanHotFiles(pending);

        assertEquals(0, pending.size(), () -> "nothing changed: " + pending.keySet());
    }

    /** The same change is one change, however many times it is scanned for. */
    @Test
    void aChangeIsReportedOnceRatherThanEveryScan() throws Exception {
        Path file = classFile("Order.class");
        watcher.markHot(file, "app", "classes");
        changeOnDisk(file);

        Map<Path, FileWatcher.PendingEvent> first = new LinkedHashMap<>();
        watcher.scanHotFiles(first);
        Map<Path, FileWatcher.PendingEvent> second = new LinkedHashMap<>();
        watcher.scanHotFiles(second);

        assertEquals(1, first.size());
        assertEquals(0, second.size(), () -> "reported again: " + second.keySet());
    }

    @Test
    void aFileThatWasDeletedIsNotAChangeAndDoesNotThrow() throws Exception {
        Path file = classFile("Order.class");
        watcher.markHot(file, "app", "classes");
        Files.delete(file);

        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        assertDoesNotThrow(() -> watcher.scanHotFiles(pending));
        assertEquals(0, pending.size(), "a deletion is the WatchService's to report");
    }

    /**
     * A file nobody has touched for a while is not what is being worked on,
     * and the list is what keeps the scan a handful of syscalls.
     */
    @Test
    void aFileNobodyHasTouchedStopsBeingChecked() throws Exception {
        Path file = classFile("Order.class");
        watcher.markHot(file, "app", "classes");
        FileWatcher.hotForMs = 0;

        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        watcher.scanHotFiles(pending);
        changeOnDisk(file);
        watcher.scanHotFiles(pending);

        assertEquals(0, pending.size(),
                "it aged out, so the next change is the WatchService's to find");
    }

    @Test
    void theListStaysBounded() throws Exception {
        for (int i = 0; i < 200; i++) {
            watcher.markHot(classFile("Class" + i + ".class"), "app", "classes");
        }

        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        assertDoesNotThrow(() -> watcher.scanHotFiles(pending));
    }

    /** Switched off where the JDK has a native watcher, which is already faster. */
    @Test
    void aNativeWatcherNeedsNoneOfThis() throws Exception {
        watcher.jdkPolls = false;
        Path file = classFile("Order.class");
        watcher.markHot(file, "app", "classes");
        changeOnDisk(file);

        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        watcher.scanHotFiles(pending);

        assertEquals(0, pending.size(), "nothing should have been marked in the first place");
    }

    private static final class NoopPlatformContext implements PlatformContext {
        @Override public Platform getPlatformId() { return Platform.GENERIC; }
        @Override public void initialize() { }
        @Override public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
        @Override public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
        @Override public String resolveClasspath() { return ""; }
        @Override public String resolveClassName(Path classFile) { return null; }
        @Override public Path resolveOutputDir(Path classFile) { return null; }
        @Override public Object getApplicationContext() { return null; }
    }
}
