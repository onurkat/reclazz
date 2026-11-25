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

            Set<Path> savedSources=new HashSet<>();
            modules.values().forEach(sources->sources.forEach(p->savedSources.add(p.toAbsolutePath().normalize())));
            Map<Path, byte[]> files = new LinkedHashMap<>();
            Map<String, byte[]> classes = new LinkedHashMap<>();
            List<Output> emitted = new ArrayList<>();
            Set<String> byproducts = new LinkedHashSet<>();
            for (var output : outputs.entrySet()) {
                try (var walk = Files.walk(output.getValue())) {
                    for (Path file : walk.filter(Files::isRegularFile)
                            .filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                        Path relative = output.getValue().relativize(file);
                        byte[] bytes = Files.readAllBytes(file);
                        String name = relative.toString().replace(java.io.File.separatorChar, '.');
                        name=name.substring(0,name.length()-6);
                        boolean own = ownSource(bytes, savedSources);
                        Path directory = compiler.publicationDir(output.getKey(), relative);
                        emitted.add(new Output(directory.resolve(relative), name, bytes, own,
                                compiler.mayPublish(directory, bytes)));
                        if (!own) byproducts.add(name);
                    }
                }
            }
            var proof = compiler.byproductProofFor(byproducts);
            for (Output output : emitted) {
                if (!output.own && proof.test(output.name, output.bytes)) continue;
                if (output.publish) files.put(output.path, output.bytes);
                classes.put(output.name, output.bytes);
            }
            Path generated = compiler.generatedDirectory();
            if (generated != null) {
                for (Path staging : outputs.values()) {
                    Path sourceStage = MavenJavac.generatedStage(staging);
                    try (var walk = Files.walk(sourceStage)) {
                        for (Path source : walk.filter(Files::isRegularFile).toList())
                            files.put(generated.resolve(sourceStage.relativize(source)), Files.readAllBytes(source));
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

    private record Output(Path path, String name, byte[] bytes, boolean own, boolean publish) { }

    private static boolean ownSource(byte[] bytes,Set<Path> saved) {
        var node=new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node,org.objectweb.asm.ClassReader.SKIP_CODE|org.objectweb.asm.ClassReader.SKIP_FRAMES);
        if(node.sourceFile==null)return true; // Unknown provenance is never skip authority.
        int slash=node.name.lastIndexOf('/');
        Path relative=Path.of((slash<0 ? "" : node.name.substring(0,slash+1))+node.sourceFile);
        if(saved.stream().anyMatch(p->p.endsWith(relative)))return true;
        // javac also accepts a source outside its package's directory layout.
        // An ambiguous same basename is conservatively an own output.
        return saved.stream().anyMatch(p->p.getFileName().toString().equals(node.sourceFile));
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
    /**
     * Take back the files that were queued only because they read a changed
     * constant. Nobody saved them; held after a failed compile they would join
     * every later batch and fail it the same way. Returns what was dropped.
     */
    public static <T> List<Path> dropSpeculative(Map<Path, T> pending, Set<Path> speculative) {
        synchronized (pending) {
            List<Path> dropped = new ArrayList<>();
            for (Path path : speculative) {
                if (pending.remove(path) != null) dropped.add(path);
            }
            speculative.clear();
            return dropped;
        }
    }

    public static <T> void retainExisting(Map<Path, T> pending, Map<Path, T> attempted) {
        synchronized (pending) {
            attempted.forEach((path, value) -> {
                if (Files.isRegularFile(path)) pending.putIfAbsent(path, value);
            });
        }
    }
}
