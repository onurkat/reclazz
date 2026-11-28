/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import com.onurkat.reclazz.util.BoundedProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** A fresh private Ant workspace within the installation writer's lock. */
final class NativeBuildWorkspace implements AutoCloseable {
    private final Path platform;
    final Path path;

    NativeBuildWorkspace(Path platform) throws IOException {
        this.platform = platform;
        this.path = Files.createTempDirectory(platform.resolve(".reclazz-codegen"), "work-");
    }

    Properties captureSettings() throws IOException {
        copyTarget("capture.xml");
        run("capture.xml", "reclazz-settings");
        return properties("compiler.properties");
    }

    void generate(Properties settings) throws IOException {
        copyTarget("stage.xml");
        store("compiler.properties", settings);
        var agent = new Properties();
        try {
            agent.setProperty("reclazz.agent", Path.of(NativeBeanStaging.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString());
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Cannot locate the agent build helper", e);
        }
        store("agent.properties", agent);
        run("stage.xml", "generate");
    }

    void compile(List<Path> changed) throws IOException {
        Path selected = Files.createDirectory(path.resolve("selected"));
        for (Path source : changed) {
            Path dest = selected.resolve(source);
            Files.createDirectories(dest.getParent());
            Files.copy(path.resolve("sources").resolve(source), dest);
        }
        run("stage.xml", "compile-selected");
    }

    Properties properties(String name) throws IOException {
        var values = new Properties();
        try (var in = Files.newInputStream(path.resolve(name))) { values.load(in); }
        return values;
    }

    private void store(String name, Properties values) throws IOException {
        try (var out = Files.newOutputStream(path.resolve(name))) { values.store(out, null); }
    }

    private void copyTarget(String target) throws IOException {
        try (var in = NativeBuildWorkspace.class.getResourceAsStream(target)) {
            if (in == null) throw new IOException("Missing native Ant target");
            Files.copy(in, path.resolve(target));
        }
    }

    private void run(String file, String target) throws IOException {
        boolean windows = CodegenReloader.isWindows();
        String workName = path.getFileName().toString();
        if (!workName.matches("work-[a-zA-Z0-9-]+")) throw new IOException("Unsupported workspace name");
        // Only a JDK-generated basename and our fixed target names enter the command.
        String args = "ant -f .reclazz-codegen/" + workName + "/" + file + " " + target;
        List<String> command = windows ? List.of("cmd.exe", "/c", "call setantenv.bat && " + args)
                : List.of("/bin/bash", "-c", "source ./setantenv.sh && " + args);
        try {
            var invocation = new ProcessBuilder(command).directory(platform.toFile());
            var result = BoundedProcess.run(invocation, Duration.ofMinutes(5), ignored -> {}, 1000);
            if (result.timedOut() || result.exitCode() != 0) throw new IOException("Native Ant stage failed (" + target + ")");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Native Ant stage interrupted", interrupted);
        }
    }

    @Override public void close() throws IOException {
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
