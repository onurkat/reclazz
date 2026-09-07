/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A scan on request enqueues what changed since the watcher last saw it,
 * as due, and nothing else.
 */
class ScanOnRequestTest {

    @TempDir
    Path tempDir;

    private FileWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse(null));
    }

    @Test
    void aScanFindsTheChangedAndTheNewAndSkipsTheRest() throws Exception {
        Path pkg = Files.createDirectories(tempDir.resolve("com").resolve("acme"));
        Path same = pkg.resolve("Same.class");
        Path changed = pkg.resolve("Changed.class");
        Files.write(same, new byte[]{1, 2, 3});
        Files.write(changed, new byte[]{1, 2, 3});
        long past = System.currentTimeMillis() - 10_000;
        Files.setLastModifiedTime(same, FileTime.fromMillis(past));
        Files.setLastModifiedTime(changed, FileTime.fromMillis(past));

        watcher.registerRecursive(tempDir, "acme", "classes");
        watcher.prepopulateContentHashes(tempDir, null);       // the baseline: both seen

        // A build rewrites one and adds one.
        Files.write(changed, new byte[]{4, 5, 6});
        Files.setLastModifiedTime(changed, FileTime.fromMillis(past + 5_000));
        Path added = pkg.resolve("Added.class");
        Files.write(added, new byte[]{7});
        Files.writeString(pkg.resolve("notes.txt"), "not a class");

        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        long debounce = 200;
        int found = watcher.scanWatchedDirectories(pending, debounce);

        assertEquals(2, found, pending.keySet().toString());
        assertEquals(ChangeEvent.Type.MODIFIED, pending.get(changed).type());
        assertEquals(ChangeEvent.Type.CREATED, pending.get(added).type());
        assertFalse(pending.containsKey(same), "unchanged since the baseline");
        long now = System.currentTimeMillis();
        for (FileWatcher.PendingEvent p : pending.values()) {
            assertTrue(now - p.timestamp() >= debounce, "due now, the build is over: " + p.path());
        }
        assertFalse(FileWatcher.dueNow(pending.values(), now, debounce).isEmpty(), "and the settle check agrees");

        // The same scan again finds nothing: what it enqueued is now seen.
        Map<Path, FileWatcher.PendingEvent> again = new LinkedHashMap<>();
        assertEquals(0, watcher.scanWatchedDirectories(again, debounce), again.keySet().toString());
    }

    @Test
    void aScanOfNothingRegisteredFindsNothing() {
        Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
        assertEquals(0, watcher.scanWatchedDirectories(pending, 200));
        assertTrue(pending.isEmpty());
    }

    @Test
    void synchronousScanIncludesANewPackageBeforeItReturns() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse("startupDelaySec=0,debounceMs=5000"));
        watcher.registerRecursive(tempDir, "acme", "classes");
        var delivered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var seen = new java.util.concurrent.CopyOnWriteArrayList<Path>();
        watcher.onFileChanges(events -> {
            seen.addAll(events.stream().map(ChangeEvent::getPath).toList());
            delivered.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        var thread = new Thread(watcher::startWatching);
        thread.start();
        Path nested = Files.createDirectories(tempDir.resolve("new/deep"));
        Path file = Files.write(nested.resolve("New.class"), new byte[]{1, 2, 3});
        var scan = java.util.concurrent.CompletableFuture.runAsync(watcher::scanNow);
        try {
            assertTrue(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(scan.isDone(), "scan cannot acknowledge a callback still delivering files");
            release.countDown();
            scan.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(seen.contains(file), seen.toString());
        } finally {
            release.countDown();
            watcher.stopWatching();
            thread.join(2000);
        }
    }

}
