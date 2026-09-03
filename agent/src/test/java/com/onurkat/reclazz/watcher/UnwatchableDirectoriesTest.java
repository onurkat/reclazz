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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Running out of the operating system's watches.
 *
 * <p>Watching a directory is a resource the operating system rations. On Linux
 * it is {@code fs.inotify.max_user_watches}, it is counted per user across
 * every program watching files, and an IDE indexing a large repository is a
 * heavy consumer of it. A project big enough reaches the ceiling somewhere in
 * the middle of registering its tree: measured on this SAP Commerce setup,
 * 2,222 directories under the custom extensions alone, against a limit that is
 * 8,192 on older distributions and shared with everything else.
 *
 * <p>What happened at the ceiling was the worst available outcome. The failed
 * registration threw, the throw reached the place the watcher starts, and the
 * poll loop never ran: one line during start-up and then nothing reloading
 * again, for the rest of the session, with the developer's edits landing in a
 * tree nobody was watching.
 *
 * <p>Now the walk continues and watches what it can, which is nearly all of it,
 * and says what it could not, since a partial watch that nobody mentions is the
 * same silence by a smaller amount.
 */
class UnwatchableDirectoriesTest {

    @TempDir
    Path tempDir;

    private FileWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
    }

    @Test
    void anOrdinaryTreeRefusesNothingAndSaysNothing() {
        assertEquals(0, watcher.unwatchableCount());
        assertEquals("", captureStdout(() -> watcher.reportUnwatchable()).trim());
    }

    @Test
    void whatCouldNotBeWatchedIsCountedAndNamed() throws IOException {
        Path first = Files.createDirectories(tempDir.resolve("app/one"));
        Path second = Files.createDirectories(tempDir.resolve("app/two"));
        watcher.noteUnwatchable(first, new FileSystemException(first.toString(),
                null, "User limit of inotify watches reached"));
        watcher.noteUnwatchable(second, new FileSystemException(second.toString()));

        String said = captureStdout(() -> watcher.reportUnwatchable());

        assertEquals(2, watcher.unwatchableCount());
        assertTrue(said.contains("2 directories"), () -> "the count is the scale of it: " + said);
        assertTrue(said.contains(first.toString()),
                () -> "the developer needs to know where it started: " + said);
        assertTrue(said.contains("will not reload"),
                () -> "and what it means for them: " + said);
    }

    /**
     * The remedy matters more than the diagnosis here, because the limit is
     * not this program's to raise and the developer has two ways out.
     */
    @Test
    void theMessageCarriesTheThingThatFixesIt() throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("app/one"));
        watcher.noteUnwatchable(directory, new FileSystemException(directory.toString(),
                null, "User limit of inotify watches reached"));

        String said = captureStdout(() -> watcher.reportUnwatchable());

        assertTrue(said.contains("max_user_watches"), () -> "the limit by name: " + said);
        assertTrue(said.contains("watchDirs") || said.contains("excludePatterns"),
                () -> "and the way that does not need root: " + said);
        assertTrue(said.contains("FileSystemException"),
                () -> "with what the operating system actually said: " + said);
    }

    /** One directory reads as one directory rather than as a plural. */
    @Test
    void oneIsSaidInTheSingular() throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("app/one"));
        watcher.noteUnwatchable(directory, new FileSystemException(directory.toString()));

        String said = captureStdout(() -> watcher.reportUnwatchable());

        assertTrue(said.contains("1 directory could not be watched"),
                () -> "expected the singular: " + said);
    }

    private static String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
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
