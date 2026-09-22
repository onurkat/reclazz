/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

/** Explicit whole-build barrier. Never bind this task to compile or a finalizer. */
@DisableCachingByDefault(because = "Each invocation must acquire and acknowledge a live build hold")
public abstract class ReclazzSafeBuildTask extends DefaultTask {
    @Internal public abstract RegularFileProperty getMcpJar();
    @Internal public abstract Property<Integer> getPort();
    @Internal public abstract Property<String> getOwner();
    @Internal public abstract Property<Integer> getTimeoutMs();
    @Internal public abstract Property<String> getGradleExecutable();
    @Internal public abstract ListProperty<String> getBuildArguments();
    @Internal public abstract DirectoryProperty getBuildDirectory();
    @Inject protected abstract ExecOperations getExecOperations();

    @TaskAction public void buildSafely() throws IOException {
        if (System.getenv("RECLAZZ_SAFE_BUILD_ACTIVE") != null) {
            throw new GradleException("Nested Reclazz safe build refused; invoke ordinary tasks in buildArguments");
        }
        if (!getMcpJar().isPresent() || !getMcpJar().get().getAsFile().isFile()
                || !getPort().isPresent() || getPort().get() < 1 || getPort().get() > 65535
                || !getOwner().isPresent() || !getOwner().get().matches("[A-Za-z0-9_-]{1,64}")
                || getTimeoutMs().get() < 1 || getTimeoutMs().get() > 60000) {
            throw new GradleException("Configure mcpJar, port (1-65535), owner (1-64 ASCII letters/digits/_/-) and timeoutMs (1-60000)");
        }
        List<String> args = getBuildArguments().get();
        if (args.isEmpty() || args.stream().anyMatch(a -> a == null || a.isBlank()
                || a.equals("-t") || a.equals("--continuous"))) {
            throw new GradleException("buildArguments must name a finite build, not continuous mode");
        }
        // The parent Gradle owns its project cache lock until this task returns.
        // A separate child cache prevents a same-project nested Gradle deadlock.
        var cache = Files.createTempDirectory("reclazz-gradle-cache-");
        try {
            List<String> command = new ArrayList<>(List.of(
                    new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                    "-cp", getMcpJar().get().getAsFile().getAbsolutePath(),
                    "com.onurkat.reclazz.mcp.BuildMain", "--port", getPort().get().toString(),
                    "--owner", getOwner().get(), "--timeout-ms", getTimeoutMs().get().toString(),
                    "--", getGradleExecutable().get()));
            command.addAll(args);
            command.addAll(List.of("--project-cache-dir", cache.toString(), "--no-daemon"));
            getExecOperations().exec(spec -> {
                spec.setWorkingDir(getBuildDirectory().get().getAsFile());
                spec.commandLine(command);
                spec.environment("RECLAZZ_SAFE_BUILD_ACTIVE", "1");
            }).assertNormalExitValue();
            getLogger().lifecycle("Reclazz build acknowledged; reload still requires VERIFY evidence.");
        } finally {
            // Cache cleanup must never turn build failure into success or obscure its cause.
            try (var paths = Files.walk(cache)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
    }
}
