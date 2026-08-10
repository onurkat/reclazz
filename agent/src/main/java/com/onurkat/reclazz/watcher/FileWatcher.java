/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches application directories for file changes using Java NIO WatchService.
 *
 * Supports both Hybris (extension-based) and Spring Boot (output-directory-based) layouts.
 *
 * Default mode (autoCompile=false):
 *   Watches compiled output directories for .class file changes.
 *
 * AutoCompile mode (autoCompile=true):
 *   Watches source directories and compiles changed .java files internally.
 */
public class FileWatcher {

    private final PlatformContext platformContext;
    private final AgentConfig config;
    private final WatchService watchService;
    private final Map<WatchKey, WatchedDirectory> watchKeyMap = new ConcurrentHashMap<>();
    private static final int MAX_DEDUP_ENTRIES = 5000;
    private final Map<Path, Long> lastModifiedMap = new ConcurrentHashMap<>();
    /**
     * Content-hash cache for {@code .class} files — skips dispatch when
     * the file's bytes are identical to the last dispatched version.
     *
     * Motivation: Hybris's {@code ant build} regenerates every generated
     * model class from its items.xml template on every run, and javac
     * writes a new {@code .class} for each regenerated {@code .java}.
     * But the template output is deterministic, so most regenerated
     * classes come out byte-identical to their previous version. Without
     * a content check the watcher dispatches hundreds of "changed" events
     * for every items.xml save, flooding the structural reloader with
     * no-op work and occasionally hitting protected-member access bugs in
     * the companion-class path for classes that never actually changed.
     */
    private final Map<Path, Long> classContentHashes = new ConcurrentHashMap<>();

    /**
     * Modification time each file had when the baseline hashed it.
     *
     * Needed to tell two look-alike situations apart when an event arrives
     * for a file whose content hash matches the baseline:
     *   - the file was rewritten with identical bytes AFTER the baseline
     *     (a build touching everything) — its mtime moved on, skip it;
     *   - the file was rewritten BETWEEN watch registration and the
     *     baseline walk — the baseline already captured the NEW bytes while
     *     the JVM still runs the OLD ones, so the change must be dispatched
     *     or that class silently never hot-reloads again.
     * An entry is removed once its first post-baseline event is handled.
     */
    private final Map<Path, Long> baselineMtimes = new ConcurrentHashMap<>();

    /** Class files modified after JVM start, found during the baseline walk. */
    private final Map<Path, WatchedDirectory> startupChangedClasses = new ConcurrentHashMap<>();

    /**
     * When this JVM started. Class files newer than this may have been
     * rewritten by a build after the JVM loaded them.
     */
    private static final long JVM_START_MILLIS = resolveJvmStart();

    private static long resolveJvmStart() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (Throwable t) {
            // java.management not present — fall back to agent load time,
            // which is the JVM start for -javaagent.
            return System.currentTimeMillis();
        }
    }
    private volatile boolean active = true;
    private Consumer<ChangeEvent> changeHandler;

    public FileWatcher(PlatformContext platformContext, AgentConfig config) throws IOException {
        this.platformContext = platformContext;
        this.config = config;
        this.watchService = FileSystems.getDefault().newWatchService();
    }

    public void onFileChange(Consumer<ChangeEvent> handler) {
        this.changeHandler = handler;
    }

    /**
     * Start watching all configured directories.
     * Delays registration to avoid exhausting file descriptors during server startup.
     * This method blocks until stopWatching() is called.
     */
    public void startWatching() {
        try {
            int delaySec = config.getStartupDelaySec();
            if (delaySec > 0) {
                StatusReporter.info("FileWatcher will start in " + delaySec + " seconds (waiting for server startup)...");
                Thread.sleep(delaySec * 1000L);
            }

            if (!active) return;

            registerDirectories();
            pollLoop();
        } catch (IOException e) {
            StatusReporter.error("FileWatcher error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stopWatching() {
        active = false;
        try {
            watchService.close();
        } catch (IOException ignored) {}
    }

    private void registerDirectories() throws IOException {
        int watchCount = 0;

        if (config.isAutoCompile()) {
            // AutoCompile mode: watch source directories for .java changes
            for (var entry : platformContext.getSourceDirs().entrySet()) {
                String moduleName = entry.getKey();
                if (!platformContext.shouldWatch(moduleName)) continue;

                for (Path srcDir : entry.getValue()) {
                    if (Files.isDirectory(srcDir)) {
                        registerRecursive(srcDir, moduleName, "src");
                        watchCount++;
                    }
                }
            }
        } else {
            // Default mode: watch compiled class output directories for .class changes
            int prepopulatedClasses = 0;
            long prepopStart = System.currentTimeMillis();
            for (var entry : platformContext.getClassOutputDirs().entrySet()) {
                String moduleName = entry.getKey();
                if (!platformContext.shouldWatch(moduleName)) continue;

                for (Path classDir : entry.getValue()) {
                    if (Files.isDirectory(classDir)) {
                        registerRecursive(classDir, moduleName, "classes");
                        watchCount++;
                        prepopulatedClasses += prepopulateContentHashes(
                                classDir, new WatchedDirectory(classDir, moduleName, "classes"));
                    }
                }
            }
            long prepopMs = System.currentTimeMillis() - prepopStart;
            StatusReporter.info("Content-hash baseline: " + prepopulatedClasses
                    + " class files hashed in " + prepopMs + "ms — subsequent dispatches"
                    + " skip files whose bytes haven't changed");
            dispatchClassesChangedDuringStartup();
        }

        // Always watch resource directories
        for (var entry : platformContext.getResourceDirs().entrySet()) {
            String moduleName = entry.getKey();
            if (!platformContext.shouldWatch(moduleName)) continue;

            for (Path resDir : entry.getValue()) {
                if (Files.isDirectory(resDir)) {
                    // Recursive: .impex/.xml/.properties live in subfolders
                    // (e.g. resources/impex/test-data.impex) — a single-level
                    // watch never saw those changes.
                    registerRecursive(resDir, moduleName, "resources");
                    watchCount++;
                }
            }
        }

        String mode = config.isAutoCompile() ? "autoCompile (watching sources)" : "default (watching classes)";
        StatusReporter.info("Mode: " + mode);
        StatusReporter.info("Watching " + watchCount + " directories");
    }

    private void registerRecursive(Path root, String moduleName, String sourceRoot) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                registerSingle(dir, moduleName, sourceRoot);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void registerSingle(Path dir, String moduleName, String sourceRoot) throws IOException {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        watchKeyMap.put(key, new WatchedDirectory(dir, moduleName, sourceRoot));
    }

    /**
     * Walk a freshly-registered subtree and enqueue synthetic CREATE
     * events for every interesting file we find. Needed because the
     * WatchService only reports events that fire AFTER registration —
     * if a code generator creates a whole package in one go (new
     * subdirectory + new .class files inside), the file events fire
     * before the new subdirectory is registered and are lost. Called
     * from the CREATE-directory branch in pollLoop().
     *
     * Package-private for test access.
     */
    /**
     * Recover from a WatchService OVERFLOW.
     *
     * The JDK keeps a bounded per-directory event queue (~512 entries); a
     * full {@code ant build} rewrites far more than that and the kernel
     * reports OVERFLOW instead of the individual events. Skipping it — as
     * we used to — lost those changes permanently: the user's reload
     * simply never happened, with nothing in the log to explain it.
     *
     * Re-scanning the directory re-enqueues everything; the content-hash
     * baseline downstream turns unchanged files into no-ops, so the only
     * cost is one directory walk.
     *
     * Package-private for test access.
     */
    void handleOverflow(WatchedDirectory watchedDir, Map<Path, PendingEvent> pendingEvents) {
        StatusReporter.warn("File event overflow in " + watchedDir.directory()
                + " — re-scanning directory to recover dropped changes");
        enqueueExistingFiles(watchedDir.directory(), watchedDir.moduleName(),
                watchedDir.sourceRoot(), pendingEvents);
    }

    void enqueueExistingFiles(Path root, String moduleName, String sourceRoot,
                               Map<Path, PendingEvent> pendingEvents) {
        try {
            long now = System.currentTimeMillis();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (!isInterestingFile(fileName)) return FileVisitResult.CONTINUE;
                    if (config.isExcluded(fileName)) return FileVisitResult.CONTINUE;
                    pendingEvents.put(file, new PendingEvent(
                            now, file, ChangeEvent.Type.CREATED, moduleName, sourceRoot));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            StatusReporter.warn("Failed to scan new directory " + root + ": " + e.getMessage());
        }
    }

    // TODO: under heavy class-change load (e.g. a full `ant build` that
    //   rewrites hundreds of .class files in one go), individual user
    //   saves to unrelated files can see multi-second dispatch latency
    //   because the WatchService queue is saturated. Functional (event
    //   eventually fires) but noticeable in rapid edit cycles. Fix
    //   candidate: process class-file events in bulk per directory
    //   instead of one at a time.
    private void pollLoop() throws InterruptedException {
        Map<Path, PendingEvent> pendingEvents = new LinkedHashMap<>();
        long debounceMs = config.getDebounceMs();

        while (active) {
            long waitMs = pendingEvents.isEmpty() ? 1000 : Math.min(debounceMs, 100);
            WatchKey key = watchService.poll(waitMs, TimeUnit.MILLISECONDS);

            if (key != null) {
                WatchedDirectory watchedDir = watchKeyMap.get(key);

                if (watchedDir != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == OVERFLOW) {
                            handleOverflow(watchedDir, pendingEvents);
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                        Path changedFile = watchedDir.directory.resolve(pathEvent.context());

                        if (Files.isDirectory(changedFile)) {
                            if (kind == ENTRY_CREATE) {
                                try {
                                    registerRecursive(changedFile, watchedDir.moduleName, watchedDir.sourceRoot);
                                    StatusReporter.info("Now watching new directory: " + changedFile);
                                    // Race: if files landed in the new directory
                                    // between its creation and our WatchService
                                    // registration (e.g. a code generator that
                                    // creates a whole package in one go), those
                                    // file-create events never fire. Walk the
                                    // new subtree and enqueue synthetic events
                                    // for any interesting files we find.
                                    enqueueExistingFiles(changedFile, watchedDir.moduleName,
                                            watchedDir.sourceRoot, pendingEvents);
                                } catch (IOException e) {
                                    StatusReporter.error("Failed to watch new directory: " + e.getMessage());
                                }
                            }
                            continue;
                        }

                        String fileName = changedFile.getFileName().toString();
                        if (!isInterestingFile(fileName)) continue;
                        if (config.isExcluded(fileName)) continue;

                        ChangeEvent.Type eventType = switch (kind.name()) {
                            case "ENTRY_CREATE" -> ChangeEvent.Type.CREATED;
                            case "ENTRY_DELETE" -> ChangeEvent.Type.DELETED;
                            default -> ChangeEvent.Type.MODIFIED;
                        };

                        pendingEvents.put(changedFile, new PendingEvent(
                                System.currentTimeMillis(), changedFile, eventType,
                                watchedDir.moduleName, watchedDir.sourceRoot));
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    watchKeyMap.remove(key);
                    if (watchKeyMap.isEmpty()) {
                        StatusReporter.warn("All watch directories became invalid. Stopping watcher.");
                        break;
                    }
                }
            }

            // Dispatch events whose debounce period has elapsed
            if (!pendingEvents.isEmpty()) {
                long now = System.currentTimeMillis();
                var it = pendingEvents.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    PendingEvent pending = entry.getValue();
                    if (now - pending.timestamp >= debounceMs) {
                        it.remove();
                        dispatchEvent(pending);
                    }
                }
            }

            if (lastModifiedMap.size() > MAX_DEDUP_ENTRIES) {
                evictOldEntries();
            }
        }
    }

    // Package-private for test access.
    void dispatchEvent(PendingEvent pending) {
        Long lastMod = lastModifiedMap.get(pending.path);
        long now = System.currentTimeMillis();
        if (lastMod != null && (now - lastMod) < 100) {
            return;
        }
        lastModifiedMap.put(pending.path, now);

        // Content-hash dedupe for .class files. A no-op build
        // (Hybris's ant build that rewrites identical bytecode from
        // templates) fires hundreds of ENTRY_MODIFY events for
        // unchanged files. Skip any whose bytes match the last
        // dispatched version so the class reloader / structural
        // reloader doesn't waste cycles on noise — and, more
        // importantly, doesn't apply companion-class patches to
        // classes whose bytecode didn't actually change. Deleted
        // events are exempt (nothing to hash) and so are first-ever
        // sightings (no previous hash to compare against).
        if (pending.type != ChangeEvent.Type.DELETED
                && pending.path.getFileName().toString().endsWith(".class")) {
            long newHash = computeContentHash(pending.path);
            if (newHash != -1L) {
                Long prevHash = classContentHashes.put(pending.path, newHash);
                Long baselineMtime = baselineMtimes.remove(pending.path);
                if (prevHash != null && prevHash == newHash) {
                    // Content matches what we recorded. Only trust that as
                    // "nothing to do" if the file has actually been touched
                    // since the baseline; an unchanged mtime means the write
                    // raced the baseline walk, so the running JVM may still
                    // hold the previous bytes.
                    boolean racedBaseline = baselineMtime != null
                            && baselineMtime == lastModifiedMillis(pending.path);
                    if (!racedBaseline) {
                        return;
                    }
                    StatusReporter.info("Change to " + pending.path.getFileName()
                            + " landed while Reclazz was starting — reloading it now");
                }
            }
        }

        ChangeEvent changeEvent = new ChangeEvent(
                pending.path, pending.type,
                pending.moduleName, pending.sourceRoot);

        if (changeHandler != null) {
            try {
                changeHandler.accept(changeEvent);
            } catch (Exception e) {
                StatusReporter.error("Error in change handler: " + e.getMessage());
            }
        }
    }

    /**
     * Walk a class output directory and pre-populate {@link #classContentHashes}
     * with CRC32 hashes for every {@code .class} file we find. Called once
     * at startup, before the watcher starts polling.
     *
     * <p>Why this exists: without a baseline, the very first {@code ant
     * build} after server start dispatches a "changed" event for every
     * regenerated .class file (the dedupe logic compares against an
     * empty cache → no previous hash → dispatch). On Hybris that means
     * hundreds of unchanged platform classes get pushed through the
     * structural reloader on the FIRST save. Pre-populating fixes
     * that — every regen that produces byte-identical output is
     * dropped at the watcher boundary.
     *
     * <p>Cost: walks the entire watched class tree once and CRC32s
     * each .class file. ~5 seconds for a full Hybris install (~50k
     * class files) at 500 MB/s read throughput. Runs synchronously
     * inside {@code registerDirectories} — by the time
     * {@code pollLoop} starts, the cache is fully primed.
     */
    /**
     * Reload classes whose files changed after the JVM started but before
     * the watcher was armed.
     *
     * A build finishing during server startup (the very window
     * {@code startupDelaySec} exists for) writes new bytes to disk while
     * the JVM has already loaded the old ones. No watch event will ever
     * fire for those files — registration happened afterwards — and the
     * content baseline records the NEW bytes, so nothing downstream can
     * tell the class is stale. The result was a class that silently never
     * hot-reloaded for the rest of the session.
     *
     * Files untouched since JVM start are exactly what the JVM loaded, so
     * they are left alone and the usual no-op damping still applies.
     */
    // Package-private for test access.
    void dispatchClassesChangedDuringStartup() {
        if (startupChangedClasses.isEmpty()) return;

        StatusReporter.info(startupChangedClasses.size() + " class file(s) changed while the JVM"
                + " was starting — reloading them so the running code matches disk");
        for (var entry : startupChangedClasses.entrySet()) {
            WatchedDirectory dir = entry.getValue();
            dispatchEvent(new PendingEvent(System.currentTimeMillis(), entry.getKey(),
                    ChangeEvent.Type.MODIFIED, dir.moduleName(), dir.sourceRoot()));
        }
        startupChangedClasses.clear();
    }

    /** Last-modified time in millis, or -1 when unreadable. */
    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    // Package-private for test access.
    int prepopulateContentHashes(Path classRoot) {
        return prepopulateContentHashes(classRoot, null);
    }

    int prepopulateContentHashes(Path classRoot, WatchedDirectory owner) {
        final int[] count = {0};
        try {
            Files.walkFileTree(classRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".class")) {
                        long h = computeContentHash(file);
                        if (h != -1L) {
                            long mtime = attrs.lastModifiedTime().toMillis();
                            classContentHashes.put(file, h);
                            baselineMtimes.put(file, mtime);
                            if (owner != null && mtime > JVM_START_MILLIS) {
                                startupChangedClasses.put(file, owner);
                            }
                            count[0]++;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            StatusReporter.warn("Pre-populate hash walk failed for " + classRoot
                    + ": " + e.getMessage());
        }
        return count[0];
    }

    /**
     * Read {@code file} and return a CRC32 of its bytes, or {@code -1}
     * if the file can't be read (mid-write, vanished, etc). CRC32 is
     * massive overkill for collision resistance on our key space
     * (at most a few thousand class files per hybris install) and
     * processes ~500 MB/s on modern CPUs — the overhead per dispatch
     * is in the order of 100 microseconds for a 50 KB class file.
     */
    private long computeContentHash(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            return crc.getValue();
        } catch (IOException e) {
            return -1L;
        }
    }

    private void evictOldEntries() {
        // Evict entries older than 60 seconds. If still over limit after eviction,
        // evict progressively with a shorter cutoff rather than clearing all entries
        // (which would cause duplicate event dispatch).
        long cutoff = System.currentTimeMillis() - 60_000;
        lastModifiedMap.entrySet().removeIf(e -> e.getValue() < cutoff);

        if (lastModifiedMap.size() > MAX_DEDUP_ENTRIES / 2) {
            long aggressiveCutoff = System.currentTimeMillis() - 10_000;
            lastModifiedMap.entrySet().removeIf(e -> e.getValue() < aggressiveCutoff);
        }
    }

    private boolean isInterestingFile(String fileName) {
        return fileName.endsWith(".java") ||
               fileName.endsWith(".class") ||
               fileName.endsWith(".xml") ||
               fileName.endsWith(".properties") ||
               fileName.endsWith(".yml") ||
               fileName.endsWith(".yaml") ||
               fileName.endsWith(".impex");
    }

    record WatchedDirectory(Path directory, String moduleName, String sourceRoot) {}
    record PendingEvent(long timestamp, Path path, ChangeEvent.Type type,
                        String moduleName, String sourceRoot) {}
}
