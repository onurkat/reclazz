/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Direct, root-only whole-reactor wrapper; never a per-module lifecycle hook. */
@Mojo(name = "safe-build", aggregator = true, threadSafe = true)
public class SafeBuildMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    private MojoExecution execution;
    @Parameter(property = "reclazz.mcpJar", required = true)
    private File mcpJar;
    @Parameter(property = "reclazz.port", required = true)
    private int port;
    @Parameter(property = "reclazz.owner", required = true)
    private String owner;
    @Parameter(property = "reclazz.timeoutMs", defaultValue = "5000")
    private int timeoutMs;
    /** Use an absolute mvn/mvnw executable when it is not on PATH. */
    @Parameter(property = "reclazz.mavenExecutable", defaultValue = "mvn")
    private String mavenExecutable;
    /** Complete child arguments, including settings/profiles/properties as needed. */
    @Parameter(required = true)
    private List<String> buildArguments;

    @Override public void execute() throws MojoExecutionException {
        validateInvocation(execution.getSource(), execution.getLifecyclePhase(), session.getGoals(),
                project.getBasedir(), new File(session.getExecutionRootDirectory()),
                System.getenv("RECLAZZ_SAFE_BUILD_ACTIVE"));
        List<String> command = command();
        Process child = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(project.getBasedir()).inheritIO();
            builder.environment().put("RECLAZZ_SAFE_BUILD_ACTIVE", "1");
            child = builder.start();
            int exit = child.waitFor();
            if (exit != 0) throw new MojoExecutionException("Reclazz safe build failed (exit " + exit
                    + "); retain owner '" + owner + "' for recovery. Reload is not confirmed.");
            getLog().info("Reclazz build acknowledged; reload still requires VERIFY evidence.");
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot run Reclazz safe build", e);
        } catch (InterruptedException e) {
            if (child != null) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Reclazz safe build interrupted; do not release its hold", e);
        }
    }

    static void validateInvocation(MojoExecution.Source source, String phase, List<String> goals,
            File base, File root, String nested) throws MojoExecutionException {
        if (nested != null || source != MojoExecution.Source.CLI || phase != null || goals.size() != 1
                || !base.getAbsoluteFile().equals(root.getAbsoluteFile())) {
            throw new MojoExecutionException("Invoke safe-build alone at the reactor root, directly (no lifecycle binding or nested safe build)");
        }
    }

    List<String> command() throws MojoExecutionException {
        if (mcpJar == null || !mcpJar.isFile() || port < 1 || port > 65535
                || owner == null || !owner.matches("[A-Za-z0-9_-]{1,64}")
                || timeoutMs < 1 || timeoutMs > 60000 || mavenExecutable == null || mavenExecutable.isBlank()
                || buildArguments == null || buildArguments.isEmpty()
                || buildArguments.stream().anyMatch(a -> a == null || a.isBlank())) {
            throw new MojoExecutionException("Configure mcpJar, port, owner, timeoutMs, mavenExecutable and nonempty buildArguments");
        }
        List<String> command = new ArrayList<>(List.of(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(), "-cp",
                mcpJar.getAbsolutePath(), "com.onurkat.reclazz.mcp.BuildMain", "--port", "" + port,
                "--owner", owner, "--timeout-ms", "" + timeoutMs, "--", mavenExecutable));
        command.addAll(buildArguments);
        return command;
    }
}
