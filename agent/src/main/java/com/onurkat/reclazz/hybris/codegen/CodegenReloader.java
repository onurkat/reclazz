/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.codegen;

import com.onurkat.reclazz.hybris.ExtensionInfo;
import com.onurkat.reclazz.hybris.HybrisContext;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.ChangeEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.util.BoundedProcess;

/**
 * Tier 1 hot-reload for Hybris code generation — handles both
 * {@code *-beans.xml} (DTO classes) and {@code *-items.xml} (model
 * classes). Both file types flow through the same Hybris
 * {@code gensource} / {@code ycodegenerator} ant macro, so the reloader
 * is a single instance that detects the file kind from its suffix and
 * customises only its post-run reporting.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Watcher detects a {@code *-beans.xml} or {@code *-items.xml}
 *       change and maps it to its owning extension.</li>
 *   <li>Background thread spawns
 *       {@code bash -c "cd <platform> && source ./setantenv.sh && ant build"}.
 *       Platform-level {@code ant build} is the only target that
 *       invokes {@code gensource}.</li>
 *   <li>Before running ant, the reloader touches the XML file so
 *       Hybris's CodeGenerator doesn't short-circuit with
 *       "No changes found, skipping". The resulting watcher re-fire
 *       is suppressed via {@code lastTouchedFile} + content digest + echo window.</li>
 *   <li>Hybris generates {@code .java} into {@code platform/bootstrap/gensrc}
 *       and {@code ant build} compiles into {@code platform/bootstrap/modelclasses}.</li>
 *   <li>Agent's class watcher picks up the new bytecode and the
 *       structural reload path takes over automatically.</li>
 * </ol>
 *
 * <h2>beans.xml vs items.xml — the only functional difference</h2>
 * {@code *-items.xml} changes generate model classes tied to the Hybris
 * persistence layer: new attributes need new database columns plus a
 * refresh of the type system cache. Reclazz can regenerate the class
 * bytecode automatically but cannot safely run DDL against a live
 * database — the user must click <b>HAC → Platform → Update Running
 * System</b> after the regen finishes. The reloader prints an explicit
 * reminder whenever an items.xml save is in the batch.
 *
 * <h2>Single instance serialises ant runs</h2>
 * Both kinds share one {@link #lock} + {@link #running} flag. A
 * simultaneous save of beans.xml + items.xml doesn't fork two parallel
 * ant subprocesses — the second save hits the coalesce path and waits
 * for the current run to finish. {@code ant build} is platform-wide
 * anyway, so a single run regenerates both kinds regardless of which
 * file triggered it.
 */
public class CodegenReloader {

    /**
     * Window during which a file-watcher event for a just-touched XML file
     * is treated as an echo of our own touch, not a new user save.
     */
    private static final long OWN_TOUCH_ECHO_WINDOW_MS = 3_000L;

    /** Distinguishes the two supported Hybris code-generation sources. */
    public enum Kind {
        BEANS,
        ITEMS;

        static Kind fromFileName(String fileName) {
            if (fileName.endsWith("-beans.xml")) return BEANS;
            if (fileName.endsWith("-items.xml")) return ITEMS;
            return null;
        }

        String label() {
            return this == BEANS ? "Beans XML" : "Items XML";
        }

        String codegenSubject() {
            return this == BEANS ? "DTOs" : "model classes";
        }
    }

    private final HybrisContext hybrisContext;
    private final ScheduledExecutorService executor;
    private final Object lock = new Object();
    private volatile boolean running = false;
    private volatile boolean rerunPending = false;
    private volatile String pendingExtName = null;
    private volatile Path pendingFile = null;
    private volatile Path lastTouchedFile = null;
    private volatile long lastTouchedAtMs = 0L;
    private byte[] lastTouchedDigest; // guarded by lock, together with the touch identity

    /**
     * Kinds of XML that were saved during (or just before) the current
     * ant run — used to decide which post-run reminder messages to
     * surface (items.xml always triggers the HAC updatesystem note).
     * Guarded by {@link #lock}.
     */
    private final Set<Kind> pendingKinds = EnumSet.noneOf(Kind.class);

    public CodegenReloader(HybrisContext hybrisContext) {
        this.hybrisContext = hybrisContext;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Reclazz-CodegenRegen");
            t.setDaemon(true);
            return t;
        });
    }

    public void handle(ChangeEvent event) {
        Path file = event.getPath();
        String fileName = file.getFileName().toString();
        Kind kind = Kind.fromFileName(fileName);
        if (kind == null) return; // not one of ours — defensive

        ExtensionInfo ext = findOwningExtension(file);
        if (ext == null) {
            StatusReporter.warn(kind.label() + " changed: " + fileName
                    + " — could not map to an extension. Run `ant build` manually to apply.");
            return;
        }

        synchronized (lock) {
            if (lastTouchedFile != null
                    && System.currentTimeMillis() - lastTouchedAtMs < OWN_TOUCH_ECHO_WINDOW_MS
                    && file.toAbsolutePath().normalize().equals(lastTouchedFile)
                    && lastTouchedDigest != null
                    && java.util.Arrays.equals(lastTouchedDigest, digest(file))) return;
            pendingKinds.add(kind);
            if (running) {
                rerunPending = true;
                pendingExtName = ext.getName();
                pendingFile = file;
                StatusReporter.info(kind.label() + " changed: " + fileName
                        + " [" + ext.getName() + "] — ant still running, will retry after it finishes");
                return;
            }
            running = true;
        }

        StatusReporter.info(kind.label() + " changed: " + fileName + " [" + ext.getName() + "]");
        StatusReporter.info("Regenerating " + kind.codegenSubject()
                + " via platform `ant build` (runs in background, usually 15-30s)");

        final Path targetFile = file;
        executor.submit(() -> runLoop(ext, targetFile));
    }

    private void runLoop(ExtensionInfo firstExt, Path firstFile) {
        boolean released = false;
        try {
            ExtensionInfo ext = firstExt;
            Path file = firstFile;
            while (true) {
                Set<Kind> batchKinds;
                synchronized (lock) {
                    batchKinds = EnumSet.copyOf(pendingKinds);
                    pendingKinds.clear();
                    rerunPending = false;
                }

                // Touch the file so Hybris's CodeGenerator doesn't
                // short-circuit. See class-level docs.
                touchFile(file);

                long started = System.currentTimeMillis();
                boolean ok = runAnt(ext);
                long elapsedMs = System.currentTimeMillis() - started;

                if (ok) {
                    reportSuccess(ext, batchKinds, elapsedMs);
                } else {
                    StatusReporter.error("Codegen regen FAILED for " + ext.getName()
                            + " — ant exited non-zero. Run `ant build` manually for full output.");
                }

                synchronized (lock) {
                    if (!rerunPending) {
                        running = false;
                        released = true;
                        return;
                    }
                    String nextName = pendingExtName;
                    ExtensionInfo next = hybrisContext.getExtensions().get(nextName);
                    if (next == null) {
                        running = false;
                        released = true;
                        return;
                    }
                    ext = next;
                    file = pendingFile != null ? pendingFile : file;
                    StatusReporter.info("Codegen: pending save queued, re-running ant for " + ext.getName());
                }
            }
        } finally {
            synchronized (lock) { if (!released) running = false; }
        }
    }

    private void reportSuccess(ExtensionInfo ext, Set<Kind> batchKinds, long elapsedMs) {
        if (batchKinds.isEmpty()) {
            // Shouldn't normally happen — the first save always adds a
            // kind before the run starts — but be defensive.
            StatusReporter.success("Codegen regen+compile done for " + ext.getName()
                    + " (" + (elapsedMs / 1000) + "s)");
            return;
        }

        boolean hasBeans = batchKinds.contains(Kind.BEANS);
        boolean hasItems = batchKinds.contains(Kind.ITEMS);
        String subject;
        if (hasBeans && hasItems) {
            subject = "DTOs + model classes";
        } else if (hasBeans) {
            subject = "DTOs";
        } else {
            subject = "model classes";
        }
        StatusReporter.success("Codegen regen+compile done for " + ext.getName()
                + " — " + subject + " ready (" + (elapsedMs / 1000) + "s). "
                + "Structural reloader is applying new bytecode now.");

        if (hasItems) {
            // items.xml DOESN'T just mean "new method on a POJO". Model
            // classes are tied to the Hybris persistence layer: new
            // attributes map to new DB columns, and the type system
            // cache must be invalidated. Reclazz can't safely run DDL
            // against a live database, so the user has to click the
            // HAC update button themselves before using the new
            // attributes.
            StatusReporter.warn("items.xml changed — new attributes require a DB schema update + "
                    + "type system refresh. Open HAC → Platform → Update Running System "
                    + "(default URL: https://localhost:9002/hac/platform/update) to apply.");
            StatusReporter.warn("New attributes on existing model instances will return null "
                    + "until HAC updatesystem runs and ModelService re-fetches from the DB.");
            // Enumtype values have the same second half. A static enumtype
            // regenerates as a Java enum and Reclazz appends the constant to
            // the running JVM; a dynamic enumtype's class materialises values
            // through its own valueOf cache. Either way the platform persists
            // the value as a reference to its EnumerationValue item, and that
            // row is also created by Update Running System.
            StatusReporter.warn("The same applies to new enumtype values: the JVM side reloads, "
                    + "but the platform can persist a value only after Update Running System "
                    + "(or an ImpEx) has created its EnumerationValue item.");
        }
    }

    /**
     * How the platform's ant is started on this operating system: the files
     * that have to be there, and the command that runs it.
     *
     * <p>SAP Commerce ships {@code setantenv.sh} with {@code apache-ant/bin/ant}
     * for Unix and {@code setantenv.bat} with {@code ant.bat} for Windows.
     * This looked for the Unix pair only and ran bash, so on Windows, where a
     * good share of Commerce development happens, every items.xml save
     * reported that the platform's ant was not found and codegen was left to
     * the developer.
     */
    record AntLaunch(Path setEnv, Path antBin, java.util.List<String> command) {
        static AntLaunch forPlatform(Path platform, boolean windows) {
            if (windows) {
                return new AntLaunch(
                        platform.resolve("setantenv.bat"),
                        platform.resolve("apache-ant").resolve("bin").resolve("ant.bat"),
                        // The working directory is set on the process, never
                        // interpolated: a path with a space or a special
                        // character would otherwise be parsed by the shell.
                        java.util.List.of("cmd.exe", "/c", "call setantenv.bat && ant build"));
            }
            return new AntLaunch(
                    platform.resolve("setantenv.sh"),
                    platform.resolve("apache-ant").resolve("bin").resolve("ant"),
                    java.util.List.of("/bin/bash", "-c", "source ./setantenv.sh && ant build"));
        }

        boolean present() {
            return Files.exists(setEnv) && Files.exists(antBin);
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private boolean runAnt(ExtensionInfo ext) {
        Path platform = hybrisContext.getHybrisHome().resolve("bin").resolve("platform");
        AntLaunch launch = AntLaunch.forPlatform(platform, isWindows());

        if (!launch.present()) {
            StatusReporter.warn("Codegen: Hybris platform ant not found at " + platform
                    + " (" + launch.setEnv().getFileName() + " and " + launch.antBin().getFileName()
                    + ") — Tier 1 ant strategy skipped. Run `ant build` manually.");
            return false;
        }


        try {
            ProcessBuilder pb = new ProcessBuilder(launch.command());
            pb.directory(platform.toFile());

            // The output used to be read to its end before the five-minute
            // wait began, so an ant that hung with its output open held the
            // reload thread for good, and every save after it with it.
            java.util.concurrent.atomic.AtomicInteger lineCount = new java.util.concurrent.atomic.AtomicInteger();
            BoundedProcess.Result run = BoundedProcess.run(
                    pb, java.time.Duration.ofSeconds(300), line -> {
                        lineCount.incrementAndGet();
                        if (line.contains("BUILD FAILED")
                                || line.contains("error:")
                                || line.contains("ERROR")) {
                            StatusReporter.warn("  " + line);
                        }
                    }, 4000);

            if (run.timedOut()) {
                StatusReporter.error("Codegen: `ant build` timed out after 5 minutes and was stopped");
                return false;
            }
            if (lineCount.get() == 0) {
                StatusReporter.warn("Codegen: ant produced no output (suspicious)");
            }

            if (run.exitCode() != 0) {
                StatusReporter.error("Codegen: ant exit code " + run.exitCode());
                for (String failLine : run.tail().split("\n")) {
                    if (failLine.isEmpty()) continue;
                    StatusReporter.error("  " + failLine);
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            StatusReporter.error("Codegen: failed to invoke ant: " + Failures.describe(e));
            return false;
        }
    }

    private void touchFile(Path file) {
        synchronized (lock) {
            try {
                // Capture content before touching. A real edit racing with the
                // touch then differs and must be queued, even inside the window.
                lastTouchedDigest = digest(file);
                lastTouchedFile = file.toAbsolutePath().normalize();
                lastTouchedAtMs = System.currentTimeMillis();
                Files.setLastModifiedTime(file, FileTime.fromMillis(lastTouchedAtMs));
            } catch (Exception ignored) { lastTouchedDigest = null; }
        }
    }

    private static byte[] digest(Path file) {
        try (var in = Files.newInputStream(file)) {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
            return hash.digest();
        } catch (Exception e) { return null; }
    }

    /** Package-private for test access. */
    ExtensionInfo findOwningExtension(Path beansFile) {
        Path abs = beansFile.toAbsolutePath().normalize();
        ExtensionInfo best = null;
        int bestLen = -1;
        for (ExtensionInfo ext : hybrisContext.getExtensions().values()) {
            Path extAbs = ext.getPath().toAbsolutePath().normalize();
            if (abs.startsWith(extAbs) && extAbs.toString().length() > bestLen) {
                best = ext;
                bestLen = extAbs.toString().length();
            }
        }
        return best;
    }
}
