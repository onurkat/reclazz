/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.transform.TransformContext;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers the question a developer actually asks: why did my class not reload?
 *
 * Almost every time it is asked, the reload never started. The build did not
 * run, the class is not under a watched directory, the bytes came out
 * identical, or the JVM has not loaded the class yet and there is nothing to
 * reload in the first place. None of those produce an error, so the log is
 * silent and silence looks like a broken tool.
 *
 * This looks at the same facts the agent used and reports them in order, so
 * the answer is either the reason or, when everything checks out, the last
 * thing that did happen to that class.
 */
public final class ReloadDiagnostics {

    /** Bounded: a long session must not accumulate a record per class forever. */
    private static final int MAX_REMEMBERED = 512;

    /** The most recent outcome per class, oldest evicted first. */
    private final Map<String, Outcome> outcomes =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Outcome> eldest) {
                    return size() > MAX_REMEMBERED;
                }
            });

    private final Instrumentation instrumentation;
    private final PlatformContext platformContext;
    private final TransformContext transformContext;
    private final Instant startedAt;

    public ReloadDiagnostics(Instrumentation instrumentation,
                             PlatformContext platformContext,
                             TransformContext transformContext,
                             Instant startedAt) {
        this.instrumentation = instrumentation;
        this.platformContext = platformContext;
        this.transformContext = transformContext;
        this.startedAt = startedAt;
    }

    public void record(String className, boolean success, String detail) {
        outcomes.put(className, new Outcome(Instant.now(), success, detail));
    }

    /**
     * @param query a fully qualified name, or a simple name when that is all
     *              the developer has in front of them
     * @return one line per finding, in the order they matter
     */
    public List<String> explain(String query) {
        List<String> report = new ArrayList<>();
        if (query == null || query.isBlank()) {
            report.add("Give a class name, for example com.example.OrderService or OrderService.");
            return report;
        }

        String name = query.trim();
        report.add("Reclazz diagnosis for " + name);

        List<Path> classFiles = findClassFiles(name);
        String resolved = classFiles.isEmpty() ? name : classNameOf(classFiles.get(0), name);

        reportBuildOutput(report, name, classFiles);
        reportLoadState(report, resolved);
        if (!classFiles.isEmpty()) {
            reportLastOutcome(report, resolved, newestBuild(classFiles));
        }
        return report;
    }

    /**
     * The compiled file is where most answers are: whether it exists at all,
     * whether the directory it sits in is one Reclazz watches, and whether the
     * build that was supposed to produce it actually ran.
     */
    private void reportBuildOutput(List<String> report, String name, List<Path> classFiles) {
        if (classFiles.isEmpty()) {
            report.add("No compiled .class file for this name under any watched directory.");
            report.add("Either the build has not run, or this class is built somewhere "
                    + "Reclazz is not watching. Watched output directories: "
                    + describeWatchedDirs());
            return;
        }

        for (Path file : classFiles) {
            String when = "unknown";
            try {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                when = ago(modified);
                if (modified.isBefore(startedAt)) {
                    when += ", which is before this JVM started";
                }
            } catch (Exception unreadable) {
                // The path is still worth printing.
            }
            report.add("Compiled file: " + file + " (last built " + when + ")");
        }

        if (classFiles.size() > 1) {
            report.add("More than one build output holds this class. The JVM uses whichever "
                    + "its classloader found first, so an edit to the other one changes nothing.");
        }
    }

    private void reportLoadState(List<String> report, String className) {
        List<Class<?>> loaded = loadedCopiesOf(className);

        if (loaded.isEmpty()) {
            report.add("The JVM has not loaded this class yet, so there is nothing to "
                    + "reload. It will use the current code the first time it is used.");
            return;
        }
        if (loaded.size() > 1) {
            report.add("Loaded " + loaded.size() + " times, by different classloaders. A reload "
                    + "reaches the copies the agent can see; a copy loaded by a classloader "
                    + "that has since been discarded is not one of them.");
        }

        String internalName = className.replace('.', '/');
        if (transformContext == null) {
            report.add("The agent has no watched-class state, which means it did not finish "
                    + "starting up.");
            return;
        }
        if (transformContext.isWatched(internalName)
                && transformContext.getMetadata(internalName) != null) {
            // Saying so matters: without it the report reads as though the
            // load state was never looked at, and the developer goes hunting
            // for a classloader problem that is not there.
            report.add("Loaded and prepared by the agent, so a reload of it would be applied.");
        }
        if (!transformContext.isWatched(internalName)) {
            report.add("Loaded, but not in the watched set. On a stock JDK this class can still "
                    + "take method-body changes; adding or removing a member needs the agent to "
                    + "have prepared it, which happens when it is loaded from a watched "
                    + "directory or reloaded once.");
        }
        if (transformContext.getMetadata(internalName) == null) {
            report.add("This class was loaded before the agent arrived, so it carries none of "
                    + "the agent's infrastructure. Method bodies still reload; adding a member "
                    + "needs a restart with the agent on the command line.");
        }
    }

    private void reportLastOutcome(List<String> report, String className, Instant newestBuild) {
        Outcome outcome = outcomes.get(className);
        if (outcome == null) {
            if (newestBuild != null && newestBuild.isBefore(startedAt)) {
                report.add("Reclazz has not attempted a reload of this class, and the compiled "
                        + "file has not been rebuilt since this JVM started. The build did not "
                        + "reach it.");
            } else {
                report.add("Reclazz has not attempted a reload of this class. The file was "
                        + "rebuilt, so its bytes came out identical to the ones already loaded "
                        + "and there was nothing to apply: a rebuild of unchanged source does "
                        + "that.");
            }
            return;
        }
        report.add("Last attempt " + ago(outcome.when) + ": "
                + (outcome.success ? "reloaded" : "failed")
                + (outcome.detail == null || outcome.detail.isBlank() ? "" : " (" + outcome.detail + ")"));
    }

    private static Instant newestBuild(List<Path> classFiles) {
        Instant newest = null;
        for (Path file : classFiles) {
            try {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (newest == null || modified.isAfter(newest)) newest = modified;
            } catch (Exception unreadable) {
                // Nothing to compare against; the caller handles null.
            }
        }
        return newest;
    }

    /**
     * Looks for the class in the same directories the watcher does, accepting
     * a simple name because that is what is on screen when the question comes
     * up.
     */
    private List<Path> findClassFiles(String name) {
        String relative = name.contains(".")
                ? name.replace('.', java.io.File.separatorChar) + ".class"
                : null;
        String simpleFileName = (name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1)
                : name) + ".class";

        List<Path> found = new ArrayList<>();
        if (platformContext == null) return found;

        for (List<Path> dirs : platformContext.getClassOutputDirs().values()) {
            for (Path dir : dirs) {
                if (relative != null) {
                    Path direct = dir.resolve(relative);
                    if (Files.isRegularFile(direct)) {
                        found.add(direct);
                        continue;
                    }
                }
                findByFileName(dir, simpleFileName, found);
            }
            if (found.size() > 8) break;
        }
        return found;
    }

    private static void findByFileName(Path dir, String fileName, List<Path> found) {
        if (!Files.isDirectory(dir)) return;
        try (java.util.stream.Stream<Path> files = Files.walk(dir, 12)) {
            files.filter(p -> p.getFileName().toString().equals(fileName))
                 .limit(4)
                 .forEach(found::add);
        } catch (Exception unwalkable) {
            // A directory that cannot be read is not an answer, just a gap.
        }
    }

    private String classNameOf(Path classFile, String fallback) {
        if (platformContext == null) return fallback;
        String resolved = platformContext.resolveClassName(classFile);
        return resolved != null ? resolved : fallback;
    }

    private List<Class<?>> loadedCopiesOf(String className) {
        List<Class<?>> loaded = new ArrayList<>();
        if (instrumentation == null) return loaded;

        boolean simpleNameOnly = !className.contains(".");
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            String name = candidate.getName();
            boolean match = simpleNameOnly
                    ? name.endsWith("." + className)
                    : name.equals(className);
            if (match) loaded.add(candidate);
        }
        return loaded;
    }

    private String describeWatchedDirs() {
        if (platformContext == null) return "none";
        List<String> dirs = new ArrayList<>();
        for (List<Path> paths : platformContext.getClassOutputDirs().values()) {
            for (Path path : paths) {
                dirs.add(path.toString());
                if (dirs.size() == 3) {
                    return String.join(", ", dirs) + " and others";
                }
            }
        }
        return dirs.isEmpty() ? "none" : String.join(", ", dirs);
    }

    private static String ago(Instant when) {
        Duration since = Duration.between(when, Instant.now());
        if (since.isNegative()) return "just now";
        long seconds = since.getSeconds();
        if (seconds < 90) return seconds + "s ago";
        if (seconds < 5400) return (seconds / 60) + " min ago";
        return (seconds / 3600) + "h ago";
    }

    private record Outcome(Instant when, boolean success, String detail) {}
}
