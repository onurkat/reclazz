package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: a class file rewritten between watch registration and the
 * content-hash baseline was silently ignored FOREVER.
 *
 * The baseline captured the new bytes while the JVM was still running the
 * old ones, so when the watch event finally arrived the hash matched and
 * the dispatch was skipped as a no-op. That class then never hot-reloaded
 * again for the rest of the session, with nothing in the log — the exact
 * window `startupDelaySec` exists for (a build landing during boot).
 *
 * Found by the generic-path smoke test, which edits a class immediately
 * after startup.
 */
class BaselineRaceTest {

    @TempDir
    Path tempDir;

    private FileWatcher watcher;
    private final List<ChangeEvent> dispatched = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
        watcher.onFileChange(dispatched::add);
    }

    private FileWatcher.PendingEvent eventFor(Path file) {
        return new FileWatcher.PendingEvent(
                System.currentTimeMillis(), file, ChangeEvent.Type.MODIFIED, "m", "classes");
    }

    @Test
    void changeThatRacedTheBaselineIsStillDispatched() throws Exception {
        Path classFile = tempDir.resolve("Raced.class");
        Files.write(classFile, new byte[] {1, 2, 3});

        // Baseline sees the file as it is on disk (already the NEW bytes,
        // because the build wrote it while we were registering watches).
        watcher.prepopulateContentHashes(tempDir);

        // The watch event for that write arrives now. Bytes match the
        // baseline, yet the running JVM may still hold the old class.
        watcher.dispatchEvent(eventFor(classFile));

        assertEquals(1, dispatched.size(),
                "a write that raced the baseline must still reach the reloader");
        assertEquals(classFile, dispatched.get(0).getPath());
    }

    @Test
    void identicalRewriteAfterTheBaselineIsStillSkipped() throws Exception {
        Path classFile = tempDir.resolve("Unchanged.class");
        Files.write(classFile, new byte[] {1, 2, 3});
        watcher.prepopulateContentHashes(tempDir);

        // A build rewrote the file with identical bytes: mtime moves on,
        // content does not. This is the storm the baseline exists to damp.
        Files.setLastModifiedTime(classFile,
                java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(classFile).toMillis() + 5_000));

        watcher.dispatchEvent(eventFor(classFile));

        assertTrue(dispatched.isEmpty(),
                "an identical rewrite after the baseline must not be dispatched");
    }

    @Test
    void racedFileIsDispatchedOnlyOnce() throws Exception {
        Path classFile = tempDir.resolve("Once.class");
        Files.write(classFile, new byte[] {9, 9, 9});
        watcher.prepopulateContentHashes(tempDir);

        watcher.dispatchEvent(eventFor(classFile));
        watcher.dispatchEvent(eventFor(classFile));

        assertEquals(1, dispatched.size(),
                "the race allowance must not turn into repeated no-op reloads");
    }

    @Test
    void classesWrittenBeforeTheWatcherStartedAreReloadedOnce() throws Exception {
        // The harder half of the same bug: when the build finishes BEFORE
        // watch registration, no event ever fires. The baseline walk must
        // notice the file is newer than JVM start and reload it itself.
        Path classFile = tempDir.resolve("WrittenDuringBoot.class");
        Files.write(classFile, new byte[] {7, 7, 7});

        watcher.prepopulateContentHashes(tempDir,
                new FileWatcher.WatchedDirectory(tempDir, "m", "classes"));
        watcher.dispatchClassesChangedDuringStartup();

        assertEquals(1, dispatched.size(),
                "a class written while the JVM was booting must be reloaded");
        assertEquals(classFile, dispatched.get(0).getPath());

        // And exactly once — a second sweep must not re-fire it.
        watcher.dispatchClassesChangedDuringStartup();
        assertEquals(1, dispatched.size());
    }

    @Test
    void genuineContentChangeIsAlwaysDispatched() throws Exception {
        Path classFile = tempDir.resolve("Changed.class");
        Files.write(classFile, new byte[] {1, 1, 1});
        watcher.prepopulateContentHashes(tempDir);

        Files.write(classFile, new byte[] {2, 2, 2});
        watcher.dispatchEvent(eventFor(classFile));

        assertEquals(1, dispatched.size(), "changed bytes must always dispatch");
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
        @Override public List<Object> getAllApplicationContexts() { return new ArrayList<>(); }
    }
}
