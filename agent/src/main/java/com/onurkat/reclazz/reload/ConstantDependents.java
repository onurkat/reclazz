/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.ui.StatusReporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The sources that inlined a constant, found by the only means there is.
 *
 * <p>javac copies a compile-time constant into every use site and leaves no
 * symbolic reference behind: the dependent's bytecode holds {@code ldc 3},
 * indistinguishable from someone having written {@code 3}. So the changed
 * class cannot reach its dependents, and the tool said so, in a sentence that
 * ended with "no tool can reach them from here" and left the developer to
 * work out which files those were.
 *
 * <p>The word "here" was carrying that sentence. From the changed class,
 * nothing is reachable. From the project, the dependents are exactly the
 * sources that name the constant, and the sources are on disk. Naming them is
 * the part the developer actually needed, and when Reclazz is the compiler
 * (autoCompile), rebuilding them is one more step of work it was already
 * doing.
 *
 * <p>The match is deliberately loose: the constant's name as a word, anywhere
 * in a source file of a watched module. A file that mentions the name without
 * inlining anything gets recompiled to identical bytes, and the watcher's
 * content hash drops it without a reload, so a false positive costs one javac
 * pass and nothing else. A miss would cost a wrong value in a running server,
 * which is what this exists to prevent.
 *
 * <p>The scan is off the reload path. A constant change is rare and a source
 * tree can be large; the reload it belongs to should not wait for a walk of
 * it, and a walk that runs long is cut off and says so rather than growing
 * without a bound.
 */
public final class ConstantDependents {

    /** How many file names a message carries before it stops being readable. */
    private static final int MAX_NAMED = 8;

    /** A walk that has taken this long has stopped being a background task. */
    private static final long BUDGET_MS = 5_000;

    private ConstantDependents() {
    }

    /**
     * Find, and either rebuild or name, the sources that inlined a constant.
     *
     * @param sourceDirs module to source roots, as the platform reports them
     * @param rebuild    what to do with the files, grouped by module, or null
     *                   when the project's own build owns compilation
     */
    public static void chase(String className, List<String> constantNames,
                             Map<String, List<Path>> sourceDirs,
                             Consumer<Map<String, List<Path>>> rebuild) {
        if (constantNames.isEmpty() || sourceDirs == null || sourceDirs.isEmpty()) return;

        Thread worker = new Thread(
                () -> run(className, constantNames, sourceDirs, rebuild), "Reclazz-Constants");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(String className, List<String> constantNames,
                            Map<String, List<Path>> sourceDirs,
                            Consumer<Map<String, List<Path>>> rebuild) {
        try {
            Result found = search(className, constantNames, sourceDirs);
            String named = String.join(", ", constantNames);

            if (found.byModule.isEmpty()) {
                StatusReporter.info("No other source in the watched modules reads " + named
                        + (found.truncated ? ", as far as the scan got" : "")
                        + ", so nothing else is holding the old value.");
                return;
            }

            int count = found.count();
            if (rebuild == null) {
                StatusReporter.warn(count + " source file(s) read " + named
                        + " and were compiled with the old value: " + found.describe()
                        + ". Rebuild them and Reclazz picks the new value up; your build "
                        + "recompiles constant dependents when it is asked to compile.");
                com.onurkat.reclazz.agent.RestartLedger.note(className,
                        "a changed constant that " + count + " other source file(s) inlined "
                                + "and have not been rebuilt");
                return;
            }

            StatusReporter.info("Rebuilding " + count + " source file(s) that read " + named
                    + ": " + found.describe());
            rebuild.accept(found.byModule);
        } catch (Throwable neverBlocksAReload) {
            // The reload this belongs to finished long ago. A scan that cannot
            // run leaves exactly the behaviour that came before it.
        }
    }

    /** What the walk found, and whether it got to the end. */
    record Result(Map<String, List<Path>> byModule, boolean truncated) {

        int count() {
            return byModule.values().stream().mapToInt(List::size).sum();
        }

        String describe() {
            List<String> names = new ArrayList<>();
            for (List<Path> files : byModule.values()) {
                for (Path file : files) {
                    if (names.size() == MAX_NAMED) {
                        names.add("and " + (count() - MAX_NAMED) + " more");
                        return String.join(", ", names);
                    }
                    names.add(String.valueOf(file.getFileName()));
                }
            }
            return String.join(", ", names);
        }
    }

    static Result search(String className, List<String> constantNames,
                         Map<String, List<Path>> sourceDirs) {
        List<Pattern> patterns = new ArrayList<>();
        for (String name : constantNames) {
            patterns.add(Pattern.compile("\\b" + Pattern.quote(name) + "\\b"));
        }
        String ownFile = simpleName(className) + ".java";
        long deadline = System.currentTimeMillis() + BUDGET_MS;

        Map<String, List<Path>> byModule = new LinkedHashMap<>();
        boolean truncated = false;

        for (Map.Entry<String, List<Path>> module : sourceDirs.entrySet()) {
            for (Path root : module.getValue()) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> tree = Files.walk(root)) {
                    for (Path file : (Iterable<Path>) tree::iterator) {
                        if (System.currentTimeMillis() > deadline) {
                            truncated = true;
                            break;
                        }
                        if (!String.valueOf(file.getFileName()).endsWith(".java")) continue;
                        // The class that declares the constant is current by
                        // definition: it is the one that just reloaded.
                        if (String.valueOf(file.getFileName()).equals(ownFile)) continue;
                        if (!mentions(file, patterns)) continue;
                        byModule.computeIfAbsent(module.getKey(), k -> new ArrayList<>()).add(file);
                    }
                } catch (Exception oneRoot) {
                    // A source root that cannot be walked contributes nothing.
                }
                if (truncated) break;
            }
            if (truncated) break;
        }
        return new Result(byModule, truncated);
    }

    private static boolean mentions(Path file, List<Pattern> patterns) {
        try {
            String source = Files.readString(file);
            for (Pattern pattern : patterns) {
                if (pattern.matcher(source).find()) return true;
            }
        } catch (Exception unreadable) {
            // Not readable as text is not a dependent anybody can rebuild.
        }
        return false;
    }

    /** The outer class's simple name: a nested class lives in its file. */
    static String simpleName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }
}
