/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.AtomicWrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** All javac calls finish before any output of this attempt is published. */
public final class CompileAttempt {
    private final IncrementalCompiler compiler;

    CompileAttempt(IncrementalCompiler compiler) {
        this.compiler = compiler;
    }

    IncrementalCompiler.CompileResult compile(Map<String, List<Path>> modules) {
        Path root = null;
        long started = System.currentTimeMillis();
        try {
            root = Files.createTempDirectory("reclazz-compile-");
            Map<Path, Path> outputs = new LinkedHashMap<>();
            List<Path> visible = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            List<String> order = new ArrayList<>();
            for (String module : modules.keySet()) order(module, modules, order, new HashSet<>());
            for (String module : order) {
                for (var group : compiler.groups(module, modules.get(module)).entrySet()) {
                    Path staging = outputs.get(group.getKey());
                    if (staging == null) {
                        staging = root.resolve(Integer.toString(outputs.size()));
                        outputs.put(group.getKey(), staging);
                    }
                    List<String> failures = compiler.compileStaged(module, group.getValue(), staging, visible);
                    errors.addAll(failures);
                    if (failures.isEmpty() && !visible.contains(staging)) visible.add(staging);
                }
            }
            if (!errors.isEmpty()) return IncrementalCompiler.CompileResult.failure(errors);

            Map<Path, byte[]> files = new LinkedHashMap<>();
            Map<String, byte[]> classes = new LinkedHashMap<>();
            for (var output : outputs.entrySet()) {
                try (var walk = Files.walk(output.getValue())) {
                    for (Path file : walk.filter(Files::isRegularFile)
                            .filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                        Path relative = output.getValue().relativize(file);
                        byte[] bytes = Files.readAllBytes(file);
                        files.put(output.getKey().resolve(relative), bytes);
                        String name = relative.toString().replace(java.io.File.separatorChar, '.');
                        classes.put(name.substring(0, name.length() - 6), bytes);
                    }
                }
            }
            for (var file : files.entrySet()) {
                try {
                    AtomicWrite.bytes(file.getKey(), file.getValue());
                } catch (IOException failure) {
                    return IncrementalCompiler.CompileResult.failure(List.of("Could not publish "
                            + file.getKey() + ": " + Failures.describe(failure)
                            + ". No reload applied; earlier output replacements may remain on disk."));
                }
            }
            return IncrementalCompiler.CompileResult.success(classes, System.currentTimeMillis() - started);
        } catch (Exception failure) {
            return IncrementalCompiler.CompileResult.failure(List.of("Compilation exception: " + Failures.describe(failure)));
        } finally {
            if (root != null) {
                try (var walk = Files.walk(root)) {
                    for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (IOException failure) {
                    StatusReporter.warn("Could not remove compile staging " + root + ": " + Failures.describe(failure));
                }
            }
        }
    }

    private void order(String module, Map<String, List<Path>> modules, List<String> order, Set<String> visiting) {
        if (order.contains(module)) return;
        if (!visiting.add(module)) throw new IllegalArgumentException("Cyclic module dependencies at " + module);
        for (String dependency : compiler.dependencies(module)) {
            if (modules.containsKey(dependency)) order(dependency, modules, order, visiting);
        }
        visiting.remove(module);
        order.add(module);
    }

    /** A failed attempt cannot resurrect sources deleted while javac was running. */
    public static <T> void retainExisting(Map<Path, T> pending, Map<Path, T> attempted) {
        synchronized (pending) {
            attempted.forEach((path, value) -> {
                if (Files.isRegularFile(path)) pending.putIfAbsent(path, value);
            });
        }
    }
}
