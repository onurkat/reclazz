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

    /**
     * Directories whose watch went away with them, kept so they can be picked
     * up again when they come back.
     *
     * <p>A clean build deletes the output tree and rebuilds it, which is a
     * thing developers do several times an hour. On Linux the watch is on the
     * inode, so the key is invalidated for good and this code dropped it and
     * never looked again: every later edit went unnoticed, and if the tree was
     * the only watched one the loop stopped outright, having said one line
     * during the noisiest part of a build. macOS hides this, because the JDK
     * has no native file-watching there and falls back to polling, which
     * re-reads the directory and never notices it left.
     */
    private final Map<Path, WatchedDirectory> lostDirectories = new ConcurrentHashMap<>();

    /**
     * Files this session has already seen change, with the mtime they were
     * last seen at, checked directly instead of waiting for the JDK to notice.
     *
     * <p>macOS has no native file watching in the JDK, so
     * {@code FileSystems.getDefault().newWatchService()} is
     * {@code sun.nio.fs.PollingWatchService}, which walks its registered
     * directories on a two-second cycle. Measured on this machine, writing a
     * class file and timing until the watcher was handed the event: 611ms to
     * 1301ms depending on where in the cycle the write landed, and in the
     * agent's own trace, 1.9 seconds of a 2.45-second save-to-live. The
     * SensitivityWatchEventModifier that used to shorten that cycle is ignored
     * by modern JDKs; measured with and without it, the numbers were identical
     * to the millisecond.
     *
     * <p>What a developer actually does is edit the same few files over and
     * over, so those files are stat-ed directly on a short cycle. It costs a
     * handful of syscalls rather than a walk of the tree, the first change to
     * any file still waits for the JDK, and every one after it does not.
     */
    private final Map<Path, WatchedFile> hotFiles = new ConcurrentHashMap<>();

    /** A file worth checking directly, and what it looked like last time. */
    private record WatchedFile(long modifiedAt, long lastChangedAt,
                               String moduleName, String sourceRoot) {}

    /** Enough for the handful of files an edit-run-edit loop touches. */
    private static final int MAX_HOT_FILES = 64;

    /**
     * After this long without changing, a file is not what is being worked on.
     * Not final so a test can reach the expiry without waiting for it.
     */
    static long hotForMs = 5 * 60 * 1000;

    /** How often the hot files are checked. */
    static final long HOT_SCAN_MS = 150;

    /**
     * Only where the JDK polls; a native watcher is already faster than this.
     * Not final so a test can exercise the path on a machine that has one.
     */
    boolean jdkPolls = watchServicePolls();

    private static boolean watchServicePolls() {
        try {
            return FileSystems.getDefault().newWatchService()
                    .getClass().getSimpleName().contains("Polling");
        } catch (Exception cannotTell) {
            return false;
        }
    }

    /** When the first directory went missing, or 0 while none are. */
    private volatile long missingSince = 0;

    /** Whether the developer has been told; said once, not once per cycle. */
    private volatile boolean missingReported = false;

    /**
     * Long enough that an ordinary clean build finishes without a word.
     * Not final so a test can ask what happens after it without waiting.
     */
    static long missingGraceMs = 30_000;
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
                StatusReporter.info("FileWatcher starts when the application reports ready, "
                        + "or in " + delaySec + " seconds, whichever comes first...");
                long waited = com.onurkat.reclazz.platform.StartupSignal.awaitReady(delaySec);
                if (com.onurkat.reclazz.platform.StartupSignal.isReady()) {
                    StatusReporter.info("Application context refreshed after " + waited
                            + "ms; watching from here instead of waiting out the "
                            + delaySec + "s cap");
                }
            }

            if (!active) return;

            registerDirectories();
            pollLoop();
        } catch (IOException e) {
            StatusReporter.error("FileWatcher error: " + com.onurkat.reclazz.ui.Failures.describe(e));
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
                        // A Spring Boot build puts application.properties here
                        // rather than in a resources directory of its own, and
                        // without a baseline the first save after startup reads
                        // as though every key in the file had changed.
                        baselinePropertyFiles(classDir);
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
                    baselinePropertyFiles(resDir);
                    watchCount++;
                }
            }
        }

        String mode = config.isAutoCompile() ? "autoCompile (watching sources)" : "default (watching classes)";
        StatusReporter.info("Mode: " + mode);
        StatusReporter.info("Watching " + watchCount + " directories");

        // The transformer keeps its last emitted bytecode per class for the
        // per-method superclass salvage, deflated. This line is the memory
        // measurement for that cache on a real server: the watcher starts
        // after the application reports ready, which is when most watched
        // classes have been loaded and transformed.
        StatusReporter.info("Last-known-good bytecode cache: "
                + com.onurkat.reclazz.transform.TransformedClassCache.classCount()
                + " classes, "
                + (com.onurkat.reclazz.transform.TransformedClassCache.deflatedBytes() / 1024)
                + " KB deflated");
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
    /**
     * Watch again anything that has come back, and say so if it has not.
     *
     * <p>Silent when it works, because a clean build is not an event: the
     * directory goes, comes back a few seconds later, and is watched again
     * with the files it came back with treated as changes, which is what they
     * are. It speaks only when something has stayed gone long enough that it
     * is no longer a build, since at that point nothing is being watched and
     * the developer is about to spend an afternoon wondering why their edits
     * do nothing.
     *
     * <p>Package-private for test access.
     */
    /**
     * A watch that has gone invalid, which on Linux is what deleting the
     * directory does and is permanent for that key.
     *
     * <p>Package-private for test access: cancelling a key produces exactly
     * the same invalidation on any operating system, which is the only way to
     * exercise this from macOS, where the JDK polls and never sees a directory
     * leave.
     */
    void noteLostDirectory(WatchedDirectory lost) {
        if (lost == null) return;
        lostDirectories.put(lost.directory(), lost);
        if (missingSince == 0) missingSince = System.currentTimeMillis();
    }

    /** Whether this directory is currently missing its watch. */
    boolean isLost(Path directory) {
        return lostDirectories.containsKey(directory);
    }

    void recoverLostDirectories(Map<Path, PendingEvent> pendingEvents) {
        if (!lostDirectories.isEmpty()) {
            for (WatchedDirectory lost : new java.util.ArrayList<>(lostDirectories.values())) {
                if (!Files.isDirectory(lost.directory())) continue;
                try {
                    registerRecursive(lost.directory(), lost.moduleName(), lost.sourceRoot());
                    lostDirectories.remove(lost.directory());
                    // What arrived while nothing was watching is a change that
                    // has not been seen yet, which is what these events say.
                    enqueueExistingFiles(lost.directory(), lost.moduleName(),
                            lost.sourceRoot(), pendingEvents);
                } catch (IOException notYet) {
                    // Half-built directories come and go during a build; the
                    // next cycle is a second away.
                }
            }
        }

        if (lostDirectories.isEmpty()) {
            if (missingReported) {
                StatusReporter.success("Watched directories are back and being watched again.");
            }
            missingSince = 0;
            missingReported = false;
            return;
        }

        // At least the grace period, not strictly more: the boundary should be
        // the same answer every time it is asked rather than depending on
        // which millisecond the question lands in.
        boolean overdue = missingSince != 0
                && System.currentTimeMillis() - missingSince >= missingGraceMs;
        if (overdue && !missingReported) {
            missingReported = true;
            StatusReporter.warn(lostDirectories.size() + " watched director"
                    + (lostDirectories.size() == 1 ? "y has" : "ies have")
                    + " been gone for over " + (missingGraceMs / 1000) + " seconds: "
                    + lostDirectories.keySet() + ". Nothing in "
                    + (lostDirectories.size() == 1 ? "it" : "them")
                    + " is being watched, so edits there will not reload. A clean build takes "
                    + "them away and brings them back within seconds and this is not that; "
                    + "check the path, or that the build still writes there. They will be "
                    + "picked up on their own the moment they exist again.");
        }
    }

    /**
     * Remember a file as one being worked on, so the next change to it is
     * noticed directly rather than on the JDK's cycle.
     */
    void markHot(Path file, String moduleName, String sourceRoot) {
        if (!jdkPolls || file == null) return;
        long now = System.currentTimeMillis();
        if (hotFiles.size() >= MAX_HOT_FILES) {
            hotFiles.entrySet().removeIf(e -> now - e.getValue().lastChangedAt() >= hotForMs);
            if (hotFiles.size() >= MAX_HOT_FILES) {
                hotFiles.entrySet().stream()
                        .min(java.util.Comparator.comparingLong(e -> e.getValue().lastChangedAt()))
                        .ifPresent(oldest -> hotFiles.remove(oldest.getKey()));
            }
        }
        hotFiles.put(file, new WatchedFile(modifiedAt(file), now, moduleName, sourceRoot));
    }

    /**
     * Check the files being worked on, and enqueue what has changed.
     *
     * <p>Package-private for test access.
     */
    void scanHotFiles(Map<Path, PendingEvent> pendingEvents) {
        if (hotFiles.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Path, WatchedFile> entry : hotFiles.entrySet()) {
            Path file = entry.getKey();
            WatchedFile seen = entry.getValue();
            // At least that long, not more than it: a boundary that depends on
            // which millisecond the question lands in gives a different answer
            // to the same state.
            if (now - seen.lastChangedAt() >= hotForMs) {
                hotFiles.remove(file);
                continue;
            }
            long modified = modifiedAt(file);
            if (modified == 0 || modified == seen.modifiedAt()) continue;

            hotFiles.put(file, new WatchedFile(modified, now, seen.moduleName(), seen.sourceRoot()));
            pendingEvents.put(file, new PendingEvent(now, file, ChangeEvent.Type.MODIFIED,
                    seen.moduleName(), seen.sourceRoot()));
        }
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException gone) {
            return 0;
        }
    }

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
            StatusReporter.warn("Failed to scan new directory " + root + ": " + com.onurkat.reclazz.ui.Failures.describe(e));
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
            long idleWait = (jdkPolls && !hotFiles.isEmpty()) ? HOT_SCAN_MS : 1000;
            long waitMs = pendingEvents.isEmpty() ? idleWait : Math.min(debounceMs, 100);
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
                                    StatusReporter.error("Failed to watch new directory: " + com.onurkat.reclazz.ui.Failures.describe(e));
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
                    // Remembered rather than forgotten: the directory is
                    // usually on its way back, and the watcher stays alive
                    // even when every one of them has gone, because a clean
                    // build takes them all and a watcher that stops there is
                    // a watcher that stops for the rest of the day.
                    noteLostDirectory(watchKeyMap.remove(key));
                }
            }

            scanHotFiles(pendingEvents);
            recoverLostDirectories(pendingEvents);

            // Dispatch events whose debounce period has elapsed
            if (!pendingEvents.isEmpty()) {
                long now = System.currentTimeMillis();
                var it = pendingEvents.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    PendingEvent pending = entry.getValue();
                    if (now - pending.timestamp >= debounceMs) {
                        it.remove();
                        markHot(pending.path(), pending.moduleName(), pending.sourceRoot());
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
                StatusReporter.error("Error in change handler: " + com.onurkat.reclazz.ui.Failures.describe(e));
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
                    + ": " + com.onurkat.reclazz.ui.Failures.describe(e));
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
    /**
     * Record what the configuration files say right now.
     *
     * A save is applied by comparing the file against its own previous content,
     * which needs a previous content to exist. The server read these files
     * minutes ago, so this is the version already reflected in the running
     * configuration, and without it the first save after a start would treat
     * every line in the file as an edit.
     */
    private void baselinePropertyFiles(Path dir) {
        // A snapshot is the whole file, held for as long as the agent runs, so
        // it is only taken for files a save could act on. On SAP Commerce that
        // is the platform's own configuration and nothing else: the other 400
        // .properties files in an installation are message bundles and library
        // settings that the property path refuses anyway, and keeping them
        // measured 1.6 MB of strings for nothing. Elsewhere the same comparison
        // is what tells a changed log level or a rebindable property from the
        // rest of an application.properties, so there it is every file.
        java.util.function.Predicate<Path> worthKeeping =
                (platformContext instanceof com.onurkat.reclazz.platform.HybrisPlatformContext)
                        ? com.onurkat.reclazz.hybris.HybrisConfigReloader::isPlatformConfiguration
                        : f -> f.getFileName().toString().endsWith(".properties");

        try (java.util.stream.Stream<Path> files = Files.walk(dir, 4)) {
            files.filter(Files::isRegularFile)
                 .filter(worthKeeping)
                 .forEach(com.onurkat.reclazz.agent.ReclazzAgent::baselinePropertyFile);
        } catch (Exception e) {
            // A directory that cannot be walked costs one over-eager first
            // save, not a broken watcher.
        }
    }

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
               fileName.endsWith(".impex") ||
               // Templates are data, not code: watched so their cache can be
               // dropped when they change.
               fileName.endsWith(".ftl") ||
               fileName.endsWith(".ftlh") ||
               fileName.endsWith(".html") ||
               fileName.endsWith(".htm");
    }

    record WatchedDirectory(Path directory, String moduleName, String sourceRoot) {}
    record PendingEvent(long timestamp, Path path, ChangeEvent.Type type,
                        String moduleName, String sourceRoot) {}
}
