/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import com.onurkat.reclazz.platform.codegen.GeneratedBuildTransaction;
import com.onurkat.reclazz.platform.codegen.GeneratedPublication;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.StatusReporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Session-local fast path. Only a successful full native generation can seed the first baseline. */
final class DtoFastBuild {
    private record Known(Properties settings, Properties extensions, GeneratedBaseline outputs,
                         Map<Path,String> dtos, NativeBuildInputs inputs) { }
    private Known known;
    private String missing = "first save in this server";
    private NativeBuildWorkspace workspace;
    private Known pending;

    GeneratedBuildTransaction.Result build(Path platform, Path saved, boolean beansOnly, BooleanSupplier full) throws IOException {
        Path bootstrap = platform.resolve("bootstrap");
        try {
            var result = GeneratedBuildTransaction.run(platform, () -> {
                if (known == null) throw new IOException(missing);
                if (!beansOnly) throw new IOException("item generation may change ordinary extension output");
                workspace = new NativeBuildWorkspace(platform);
                return stage(platform, saved);
            }, () -> {
                known = null;
                pending = null;
                if (!full.getAsBoolean()) return false;
                if (!Files.isRegularFile(bootstrap.resolve("bin/models.jar"))) {
                    missing = "native build did not produce models.jar";
                    return true;
                }
                try (var capture = new NativeBuildWorkspace(platform)) {
                    var options = capture.captureSettings();
                    NativeCompilerPolicy.classpath(platform, options);
                    capture.generate(options);
                    var outputs = GeneratedBaseline.capture(bootstrap);
                    var dtos = GeneratedSources.inventory(capture.path.resolve("sources"));
                    for (var dto : dtos.entrySet()) {
                        if (!dto.getValue().equals(outputs.sources().get(dto.getKey())))
                            throw new IOException("full build and staged DTO sources disagree");
                    }
                    var extensions = capture.properties("extensions.properties");
                    known = new Known(options, extensions, outputs, dtos,
                            NativeBuildInputs.capture(platform, options, extensions));
                } catch (IOException | RuntimeException failure) {
                    missing = "baseline unavailable: " + Failures.describe(failure);
                    StatusReporter.detail("Codegen " + missing);
                }
                return true;
            });
            if (result.success() && result.fullReason() == null) known = pending;
            return result;
        } finally {
            pending = null;
            if (workspace != null) {
                var completed = workspace;
                workspace = null;
                try { completed.close(); }
                catch (IOException cleanup) { StatusReporter.warn("Codegen staging cleanup failed: " + Failures.describe(cleanup)); }
            }
        }
    }

    private GeneratedBuildTransaction.Candidate stage(Path platform, Path saved) throws IOException {
        Path bootstrap = platform.resolve("bootstrap");
        Path jar = bootstrap.resolve("bin/models.jar");
        if (Files.size(jar) != known.outputs().jarSize() || !Files.getLastModifiedTime(jar).equals(known.outputs().jarModified()))
            throw new IOException("external platform build changed models.jar");
        NativeBuildInputs current = NativeBuildInputs.capture(platform, known.settings(), known.extensions());
        known.inputs().verify(current, saved);
        if (!known.outputs().equals(GeneratedBaseline.capture(bootstrap))) throw new IOException("generated baseline changed");
        workspace.generate(known.settings());
        if (!known.extensions().equals(workspace.properties("extensions.properties"))) throw new IOException("extension graph changed");
        Map<Path,String> generated = GeneratedSources.inventory(workspace.path.resolve("sources"));
        List<Path> changed = GeneratedSources.changes(known.dtos(), generated);
        if (changed.isEmpty()) throw new IOException("no changed DTO class confirmed");
        workspace.compile(changed);
        Map<Path,Path> compiled = NativeCompilerPolicy.expectedOutputs(workspace.path.resolve("classes"), changed);
        List<GeneratedPublication.Change> changes = new ArrayList<>();
        for (Path source : changed) {
            if (!known.dtos().containsKey(source) && known.outputs().sources().containsKey(source))
                throw new IOException("new DTO collides with existing generated source");
            Path relative = Path.of("gensrc").resolve(source);
            changes.add(new GeneratedPublication.Change(relative, GeneratedPublication.digest(bootstrap.resolve(relative)),
                    workspace.path.resolve("sources").resolve(source)));
        }
        Set<String> names = new HashSet<>();
        for (var file : compiled.entrySet()) {
            Path relative = Path.of("modelclasses").resolve(file.getKey());
            Path original = bootstrap.resolve(relative);
            String before = known.outputs().classes().getOrDefault(file.getKey(), "-");
            byte[] previous = before.equals("-") ? null : Files.readAllBytes(original);
            byte[] next = Files.readAllBytes(file.getValue());
            if (previous != null && !GeneratedClassCompatibility.preservesDeclarations(previous, next))
                throw new IOException("existing generated declaration or constant changed");
            names.addAll(GeneratedClassCompatibility.newNames(previous, next));
            changes.add(new GeneratedPublication.Change(relative, before, file.getValue()));
        }
        if (!current.possibleConsumerNames(names, Set.of()).isEmpty()) throw new IOException("ordinary consumer may require recompilation");
        Path nextJar = workspace.path.resolve("models.jar");
        StagedModelsJar.build(jar, nextJar, compiled);
        changes.add(new GeneratedPublication.Change(Path.of("bin/models.jar"), known.outputs().jarDigest(), nextJar));
        if (!GeneratedPublication.digest(saved).equals(current.inputs.get(saved.toRealPath())))
            throw new IOException("Saved generator input changed during compilation");
        pending = new Known(known.settings(), known.extensions(),
                known.outputs().afterPublication(generated, compiled, nextJar), generated, current);
        return new GeneratedBuildTransaction.Candidate(List.copyOf(changes), compiled.size());
    }
}
