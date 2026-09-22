/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeBuildMojoTest {
    @TempDir Path dir;

    @Test void onlyDirectSingleRootInvocationIsAccepted() throws Exception {
        File root = dir.toFile();
        SafeBuildMojo.validateInvocation(MojoExecution.Source.CLI, null, List.of("reclazz:safe-build"), root, root, null);
        assertThrows(MojoExecutionException.class, () -> SafeBuildMojo.validateInvocation(
                MojoExecution.Source.LIFECYCLE, "compile", List.of("compile"), root, root, null));
        assertThrows(MojoExecutionException.class, () -> SafeBuildMojo.validateInvocation(
                MojoExecution.Source.CLI, null, List.of("compile", "reclazz:safe-build"), root, root, null));
        assertThrows(MojoExecutionException.class, () -> SafeBuildMojo.validateInvocation(
                MojoExecution.Source.CLI, null, List.of("reclazz:safe-build"), new File(root, "module"), root, null));
        assertThrows(MojoExecutionException.class, () -> SafeBuildMojo.validateInvocation(
                MojoExecution.Source.CLI, null, List.of("reclazz:safe-build"), root, root, "1"));
    }

    @Test void argvPreservesSpacesWithoutShellAndRejectsInvalidInput() throws Exception {
        var mojo = new SafeBuildMojo();
        assertThrows(MojoExecutionException.class, mojo::command);
        File jar = Files.createFile(dir.resolve("mcp jar.jar")).toFile();
        set(mojo, "mcpJar", jar); set(mojo, "port", 54123); set(mojo, "owner", "test-owner");
        set(mojo, "timeoutMs", 5000); set(mojo, "mavenExecutable", "/path with spaces/mvn");
        set(mojo, "buildArguments", List.of("-Dmessage=space ; $(no-shell)", "verify"));
        List<String> command = mojo.command();
        assertEquals(jar.getAbsolutePath(), command.get(2));
        assertEquals(List.of("--", "/path with spaces/mvn", "-Dmessage=space ; $(no-shell)", "verify"),
                command.subList(command.size() - 4, command.size()));
        set(mojo, "owner", "bad\nBUILD ok"); assertThrows(MojoExecutionException.class, mojo::command);
        set(mojo, "owner", "test-owner"); set(mojo, "port", 0);
        assertThrows(MojoExecutionException.class, mojo::command);
        set(mojo, "port", 54123); set(mojo, "buildArguments", List.of());
        assertThrows(MojoExecutionException.class, mojo::command);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(field); f.setAccessible(true); f.set(target, value);
    }
}
