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
        Files.setLastModifiedTime(added, FileTime.fromMillis(System.currentTimeMillis()+1));
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
        // Make post-registration creation unambiguous even within one clock millisecond.
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()+1));
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

    private void countedWatcher() throws Exception {
        watcher = new FileWatcher(new NoopPlatformContext(), AgentConfig.parse("startupDelaySec=0,debounceMs=5000"));
        watcher.registerRecursive(tempDir, "app", "classes");
    }
    private void changedFile() throws Exception {
        var file=Files.write(tempDir.resolve("Counted.class"),new byte[]{1,2,3});
        Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()+1));
    }
    private Thread startCounted() {
        var thread=new Thread(watcher::startWatching);thread.setDaemon(true);thread.start();return thread;
    }
    private void queued(int count) throws Exception {
        var field=FileWatcher.class.getDeclaredField("scanBarriers");field.setAccessible(true);
        var queue=(java.util.Queue<?>)field.get(watcher);long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(queue.size()<count){assertTrue(System.nanoTime()<until,"scan admission deadline");Thread.yield();}
    }
    @Test void countedEmptyScanRepliesZero() throws Exception {
        countedWatcher();var request=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);queued(1);var thread=startCounted();
        try{assertEquals(0,request.get(3,java.util.concurrent.TimeUnit.SECONDS));}finally{watcher.stopWatching();thread.join(2000);}
    }
    @Test void coalescedScansCountDeliveredEventsOncePerPass() throws Exception {
        countedWatcher();changedFile();var delivered=new java.util.concurrent.atomic.AtomicInteger();watcher.onFileChanges(events->delivered.addAndGet(events.size()));
        var a=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);var b=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);queued(2);var thread=startCounted();
        try{assertEquals(1,a.get(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,b.get(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,delivered.get());
            assertEquals(0,watcher.scanNowAndCount());
        }finally{watcher.stopWatching();thread.join(2000);}
    }
    @Test void laterBarrierCannotAcknowledgeThePassAlreadyDelivering() throws Exception {
        countedWatcher();changedFile();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        watcher.onFileChanges(events->{entered.countDown();awaitCounted(release);});
        var first=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);queued(1);var thread=startCounted();
        try{assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertFalse(first.isDone());
            var later=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);queued(1);assertFalse(later.isDone());
            release.countDown();assertEquals(1,first.get(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,later.get(3,java.util.concurrent.TimeUnit.SECONDS));
        }finally{release.countDown();watcher.stopWatching();thread.join(2000);}
    }
    @Test void stopFailsQueuedScanWithoutWaitingForDeadline() throws Exception {
        countedWatcher();var request=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);queued(1);watcher.stopWatching();
        assertThrows(java.util.concurrent.ExecutionException.class,()->request.get(2,java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class,watcher::scanNowAndCount);
    }
    @Test void stopFailsAlreadyDrainedBarrierWhileCallbackIsHeld() throws Exception {
        countedWatcher();changedFile();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        watcher.onFileChanges(events->{entered.countDown();awaitCounted(release);});
        var request=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);queued(1);var thread=startCounted();
        try{assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));watcher.stopWatching();
            assertThrows(java.util.concurrent.ExecutionException.class,()->request.get(2,java.util.concurrent.TimeUnit.SECONDS));
        }finally{release.countDown();watcher.stopWatching();thread.join(2000);}
    }
    @Test void failedDeliveryCannotBecomeCountedSuccess() throws Exception {
        countedWatcher();changedFile();watcher.onFileChanges(events->{throw new IllegalStateException("deliberate callback failure");});
        var request=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);queued(1);var thread=startCounted();
        try{assertThrows(java.util.concurrent.ExecutionException.class,()->request.get(3,java.util.concurrent.TimeUnit.SECONDS));}
        finally{watcher.stopWatching();thread.join(2000);}
    }
    @Test void absentHandlerCannotClaimNonemptyEventsWereQueued() throws Exception {
        countedWatcher();changedFile();var request=java.util.concurrent.CompletableFuture.supplyAsync(watcher::scanNowAndCount);queued(1);var thread=startCounted();
        try{assertThrows(java.util.concurrent.ExecutionException.class,()->request.get(3,java.util.concurrent.TimeUnit.SECONDS));}
        finally{watcher.stopWatching();thread.join(2000);}
    }
    private static void awaitCounted(java.util.concurrent.CountDownLatch latch){try{assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}

}
