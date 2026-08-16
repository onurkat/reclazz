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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FileWatcher#enqueueExistingFiles} — the fix that
 * patches a race where Java's non-recursive {@code WatchService} would
 * miss files that landed inside a brand-new subdirectory before the
 * watcher got a chance to register it.
 *
 * <p>The method walks the freshly-created subtree and enqueues synthetic
 * {@code CREATE} events for every interesting file, so the debounce
 * pipeline downstream sees them as if the WatchService had reported them
 * in the first place.
 */
class FileWatcherEnqueueTest {

    @TempDir
    Path tempDir;

    private FileWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
    }

    @Test
    void populatedNewDirectory_emitsSyntheticCreateForEveryClassFile() throws Exception {
        // Simulate `ant build` creating a whole package in one go:
        // bootstrap/modelclasses/com/foo/bar/ with three .class files.
        Path newDir = tempDir.resolve("com").resolve("foo").resolve("bar");
        Files.createDirectories(newDir);
        Files.writeString(newDir.resolve("Alpha.class"), "");
        Files.writeString(newDir.resolve("Beta.class"), "");
        Files.writeString(newDir.resolve("Gamma.class"), "");

        Map<Path, FileWatcher.PendingEvent> queue = new LinkedHashMap<>();
        watcher.enqueueExistingFiles(tempDir, "moduleX", "classes", queue);

        assertEquals(3, queue.size(), "one synthetic event per interesting file");
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("Alpha.class")));
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("Beta.class")));
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("Gamma.class")));

        for (FileWatcher.PendingEvent event : queue.values()) {
            assertEquals("moduleX", event.moduleName());
            assertEquals("classes", event.sourceRoot());
            // All synthetic events are CREATED — we only call this path
            // from the ENTRY_CREATE-on-a-new-dir branch.
            assertEquals("CREATED", event.type().name());
        }
    }

    @Test
    void emptyNewDirectory_emitsNothing() throws Exception {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);

        Map<Path, FileWatcher.PendingEvent> queue = new LinkedHashMap<>();
        watcher.enqueueExistingFiles(emptyDir, "m", "classes", queue);

        assertTrue(queue.isEmpty(), "no events for an empty subtree");
    }

    @Test
    void newDirectoryWithUninterestingFiles_skipsThem() throws Exception {
        Path dir = tempDir.resolve("mixed");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Model.class"), "");
        Files.writeString(dir.resolve("Notes.txt"), "");
        Files.writeString(dir.resolve("README.md"), "");
        Files.writeString(dir.resolve("Service.java"), "");

        Map<Path, FileWatcher.PendingEvent> queue = new LinkedHashMap<>();
        watcher.enqueueExistingFiles(dir, "m", "classes", queue);

        // .class + .java are "interesting" by the watcher's own predicate.
        // .txt and .md are not.
        assertEquals(2, queue.size());
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("Model.class")));
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("Service.java")));
        assertFalse(queue.keySet().stream().anyMatch(p -> p.endsWith("Notes.txt")));
        assertFalse(queue.keySet().stream().anyMatch(p -> p.endsWith("README.md")));
    }

    /**
     * Regression: a WatchService OVERFLOW used to be skipped outright, so
     * the changes the kernel dropped were lost forever — the reload simply
     * never fired. Overflow must instead re-scan the affected directory.
     */
    @Test
    void overflowReScansTheDirectoryInsteadOfDroppingChanges() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("overflowing"));
        Files.writeString(dir.resolve("A.class"), "a");
        Files.writeString(dir.resolve("B.class"), "b");
        Files.writeString(dir.resolve("notes.txt"), "ignored");

        Map<Path, FileWatcher.PendingEvent> queue = new LinkedHashMap<>();
        watcher.handleOverflow(
                new FileWatcher.WatchedDirectory(dir, "moduleX", "classes"), queue);

        assertEquals(2, queue.size(), "both class files must be re-enqueued");
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("A.class")));
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("B.class")));
        assertFalse(queue.keySet().stream().anyMatch(p -> p.endsWith("notes.txt")));
    }

    @Test
    void newDirectoryWithNestedPackages_walksRecursively() throws Exception {
        // Multiple nested packages, simulating `ant beans` creating
        // `bootstrap/modelclasses/{de/hybris/...,com/foo/...}`.
        Path deep1 = tempDir.resolve("de").resolve("hybris").resolve("platform").resolve("data");
        Path deep2 = tempDir.resolve("com").resolve("foo").resolve("dto");
        Files.createDirectories(deep1);
        Files.createDirectories(deep2);
        Files.writeString(deep1.resolve("ProductData.class"), "");
        Files.writeString(deep1.resolve("OrderEntryData.class"), "");
        Files.writeString(deep2.resolve("CustomData.class"), "");

        Map<Path, FileWatcher.PendingEvent> queue = new LinkedHashMap<>();
        watcher.enqueueExistingFiles(tempDir, "m", "classes", queue);

        assertEquals(3, queue.size());
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("ProductData.class")));
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("OrderEntryData.class")));
        assertTrue(queue.keySet().stream().anyMatch(p -> p.endsWith("CustomData.class")));
    }

    /** Minimal PlatformContext stub — the watcher's constructor only stores it. */
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
