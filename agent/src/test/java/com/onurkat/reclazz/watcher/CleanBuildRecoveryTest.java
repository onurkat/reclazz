/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.AfterEach;
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
 * What a clean build does to the watch, and how it comes back.
 *
 * <p>Deleting the output tree and rebuilding it is a thing developers do
 * several times an hour. On Linux the watch is registered against the inode, so
 * the key is invalidated for good when the directory goes: the watcher dropped
 * it, never looked again, and every later edit in that tree went unnoticed. If
 * it was the only watched tree the loop stopped outright, after one line
 * printed during the noisiest part of a build.
 *
 * <p>macOS hides all of that. The JDK has no native file watching there and
 * falls back to {@code sun.nio.fs.PollingWatchService}, which re-reads the
 * directory each cycle and never notices it left; measured, a delete-and-
 * rebuild reloaded fine on this machine before any of this was written. So the
 * invalidation is produced here directly, which is the same code path the
 * inotify implementation reaches and the only honest way to test it from a Mac.
 */
class CleanBuildRecoveryTest {

    @TempDir
    Path tempDir;

    private FileWatcher watcher;

    private long originalGrace;

    @BeforeEach
    void setUp() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
        originalGrace = FileWatcher.missingGraceMs;
    }

    @AfterEach
    void restoreGrace() {
        FileWatcher.missingGraceMs = originalGrace;
    }

    private FileWatcher.WatchedDirectory lose(Path directory) {
        FileWatcher.WatchedDirectory lost =
                new FileWatcher.WatchedDirectory(directory, "app", "classes");
        watcher.noteLostDirectory(lost);
        assertTrue(watcher.isLost(directory), "the watch is gone");
        return lost;
    }

    @Test
    void aDirectoryThatComesBackIsWatchedAgain() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        lose(classes);

        watcher.recoverLostDirectories(new LinkedHashMap<>());

        assertFalse(watcher.isLost(classes),
                "the directory exists, so there is nothing left to wait for");
    }

    /**
     * The build wrote the tree while nothing was watching, so those files are
     * changes that have not been seen. Without this the recovered watch is
     * live and the class files already sitting in it are invisible until
     * somebody saves again.
     */
    @Test
    void whatArrivedWhileNothingWasWatchingCountsAsChanged() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("classes").resolve("app"));
        Files.write(classes.resolve("Order.class"), new byte[]{(byte) 0xCA, (byte) 0xFE});
        Files.write(classes.resolve("Customer.class"), new byte[]{(byte) 0xCA, (byte) 0xFE});
        lose(tempDir.resolve("classes"));

        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        watcher.recoverLostDirectories(pending);

        assertEquals(2, pending.size(), () -> "expected both class files, got " + pending.keySet());
        assertTrue(pending.containsKey(classes.resolve("Order.class")));
        assertTrue(pending.containsKey(classes.resolve("Customer.class")));
    }

    @Test
    void aDirectoryThatHasNotComeBackStaysRemembered() {
        Path gone = tempDir.resolve("never-built");
        lose(gone);

        watcher.recoverLostDirectories(new LinkedHashMap<>());

        assertTrue(watcher.isLost(gone), "it does not exist yet, so it is still waited for");
    }

    /**
     * The recovery is silent because a clean build is not an event. What is
     * not silent is a directory that stays gone long enough to no longer be a
     * build, since at that point nothing is being watched and the developer is
     * about to lose an afternoon to it.
     */
    @Test
    void aDirectoryGoneTooLongIsSaidOnceAndOnlyOnce() {
        FileWatcher.missingGraceMs = 0;
        Path gone = tempDir.resolve("never-built");
        lose(gone);

        String first = captureStdout(() -> watcher.recoverLostDirectories(new LinkedHashMap<>()));
        String second = captureStdout(() -> watcher.recoverLostDirectories(new LinkedHashMap<>()));

        assertTrue(first.contains("never-built"), () -> "it should name the directory: " + first);
        assertTrue(first.contains("will not reload"), () -> "and the consequence: " + first);
        assertEquals("", second.trim(),
                () -> "a warning once a second is noise, not information: " + second);
    }

    @Test
    void comingBackAfterThatIsWorthSayingToo() throws Exception {
        FileWatcher.missingGraceMs = 0;
        Path classes = tempDir.resolve("classes");
        lose(classes);
        captureStdout(() -> watcher.recoverLostDirectories(new LinkedHashMap<>()));

        Files.createDirectories(classes);
        String said = captureStdout(() -> watcher.recoverLostDirectories(new LinkedHashMap<>()));

        assertTrue(said.contains("back"),
                () -> "having said the watch was gone, say when it is not: " + said);
    }

    /** Nothing missing is the ordinary case, and it says nothing. */
    @Test
    void theOrdinaryCaseIsSilent() {
        assertEquals("", captureStdout(
                () -> watcher.recoverLostDirectories(new LinkedHashMap<>())).trim());
    }

    private static String captureStdout(Runnable action) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
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
