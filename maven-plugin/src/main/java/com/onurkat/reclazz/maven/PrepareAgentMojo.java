/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Prepares the Reclazz agent for the build, the way {@code jacoco:prepare-agent}
 * prepares its own: it finds the agent jar on the plugin's classpath, builds the
 * {@code -javaagent} argument, and puts it in a property (by default
 * {@code argLine}, which Surefire reads). Reference the same property from
 * {@code spring-boot:run}'s {@code jvmArguments} to reload the running app.
 *
 * <p>Bound to the {@code initialize} phase, so {@code mvn test} and
 * {@code mvn spring-boot:run} pick it up with no extra wiring.
 */
@Mojo(name = "prepare-agent",
        defaultPhase = LifecyclePhase.INITIALIZE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        threadSafe = true)
public class PrepareAgentMojo extends AbstractMojo {

    private static final String AGENT_ARTIFACT_ID = "reclazz-agent";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    /** The property to write. Surefire reads {@code argLine} on its own. */
    @Parameter(property = "reclazz.propertyName", defaultValue = "argLine")
    private String propertyName;

    /** Skip attaching the agent. */
    @Parameter(property = "reclazz.skip", defaultValue = "false")
    private boolean skip;

    /** Agent platform, for example {@code spring} or {@code hybris}. Optional. */
    @Parameter(property = "reclazz.platform")
    private String platform;

    /** Directory the agent watches for recompiled classes. */
    @Parameter(property = "reclazz.watchDirs", defaultValue = "${project.build.outputDirectory}")
    private String watchDirs;

    /** Extra agent arguments, as key/value pairs appended verbatim. */
    @Parameter
    private Map<String, String> agentArgs;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Reclazz agent not attached (reclazz.skip=true).");
            return;
        }
        File agentJar = findAgentJar();
        String arg = buildAgentArg(agentJar, platform, watchDirs, agentArgs);

        String existing = project.getProperties().getProperty(propertyName);
        String value = (existing == null || existing.isBlank()) ? arg : arg + " " + existing;
        project.getProperties().setProperty(propertyName, value);
        getLog().info("Reclazz agent set on property '" + propertyName + "': " + arg);
    }

    private File findAgentJar() throws MojoExecutionException {
        for (Artifact artifact : pluginArtifacts) {
            if (AGENT_ARTIFACT_ID.equals(artifact.getArtifactId()) && artifact.getFile() != null) {
                return artifact.getFile();
            }
        }
        throw new MojoExecutionException(
                "Could not find " + AGENT_ARTIFACT_ID + " on the plugin's classpath; "
                        + "declare it as a dependency of the plugin.");
    }

    /** Builds the {@code -javaagent:<jar>=k=v,k=v} argument. Pure, so it is unit-tested. */
    static String buildAgentArg(File agentJar, String platform, String watchDirs, Map<String, String> extra) {
        Map<String, String> args = new LinkedHashMap<>();
        if (platform != null && !platform.isBlank()) {
            args.put("platform", platform.trim());
        }
        if (watchDirs != null && !watchDirs.isBlank()) {
            args.put("watchDirs", watchDirs.trim());
        }
        if (extra != null) {
            args.putAll(extra);
        }
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> e : args.entrySet()) {
            if (joined.length() > 0) {
                joined.append(",");
            }
            joined.append(e.getKey()).append("=").append(e.getValue());
        }
        String flag = "-javaagent:" + agentJar.getAbsolutePath();
        String argument = joined.length() == 0 ? flag : flag + "=" + joined;
        if (argument.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'')) {
            return argument;
        }
        // argLine is tokenized by Maven's command-line parser. Keep this as one
        // argument; a literal double quote is a single-quoted segment between
        // double-quoted segments. Backslashes (including Windows paths) survive.
        return "\"" + argument.replace("\"", "\"'\"'\"") + "\"";
    }
}
