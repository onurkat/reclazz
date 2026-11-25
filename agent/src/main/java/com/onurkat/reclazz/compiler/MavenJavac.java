/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.config.MavenCompilerSnapshot;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Applies the captured Maven settings to one staged compilation. */
final class MavenJavac {
    private MavenJavac() {}

    static List<String> options(MavenCompilerSnapshot settings, Path staging) throws IOException {
        List<String> result = new ArrayList<>(settings.flags());
        Collections.addAll(result, "-d", staging.toString(), "-classpath", settings.classpath(),
                "-sourcepath", String.join(File.pathSeparator, settings.roots().stream().map(Path::toString).toList()),
                "-encoding", settings.encoding().name());
        if (settings.generated() != null) {
            Path temporary = generatedStage(staging);
            Files.createDirectories(temporary);
            Collections.addAll(result, "-s", temporary.toString());
        }
        return result;
    }

    static Path generatedStage(Path staging) {
        return staging.resolveSibling(staging.getFileName() + "-generated");
    }

    static List<Path> sources(MavenCompilerSnapshot settings, JavaCompiler javac, List<Path> saved) throws IOException {
        if (!settings.mapstruct()) return saved;
        // A mapper can depend on an edited DTO indirectly through uses/config. Including
        // every mapper in this module also covers those inputs without guessing imports.
        Set<Path> candidates = new LinkedHashSet<>();
        for (Path root : settings.roots()) {
            if (!Files.isDirectory(root) || root.equals(settings.generated())) continue;
            try (var walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).filter(p -> !p.startsWith(settings.generated()))
                        .forEach(candidates::add);
            }
        }
        String failure = "Cannot determine MapStruct mapper sources; use Maven to compile this project";
        if (candidates.isEmpty() || !candidates.containsAll(saved)) throw new IOException(failure);
        Set<Path> result = new LinkedHashSet<>(saved);
        boolean found = false;
        DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();
        try (var files = javac.getStandardFileManager(errors, Locale.ROOT, settings.encoding())) {
            var task = (JavacTask) javac.getTask(null, files, errors, List.of("-proc:none"), null,
                    files.getJavaFileObjectsFromPaths(candidates));
            for (CompilationUnitTree unit : task.parse()) {
                boolean[] mapper = {false};
                new TreeScanner<Void, Void>() {
                    @Override public Void visitAnnotation(AnnotationTree node, Void ignored) {
                        String name = node.getAnnotationType().toString();
                        if (name.equals("Mapper") || name.equals("org.mapstruct.Mapper")) mapper[0] = true;
                        return super.visitAnnotation(node, ignored);
                    }
                }.scan(unit, null);
                if (mapper[0]) {
                    found = true;
                    result.add(Path.of(unit.getSourceFile().toUri()));
                }
            }
        }
        if (!found || errors.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
            throw new IOException(failure);
        return List.copyOf(result);
    }
}
