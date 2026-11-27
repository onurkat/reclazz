/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import java.io.File;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugins.annotations.Component;
import org.eclipse.aether.RepositorySystem;

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
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true)
public class PrepareAgentMojo extends AbstractMojo {

    private static final String MAVEN_MARKER = "-Dreclazz.maven.prepareAgent=true";
    private static final String AGENT_ARTIFACT_ID = "reclazz-agent";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Component
    private RepositorySystem resolver;

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

    private Map<String, String> compilerArguments() {
        Map<String, String> args = new LinkedHashMap<>();
        if (agentArgs != null) args.putAll(agentArgs);
        if (project.getBasedir() == null || "pom".equals(project.getPackaging()) ||
                "false".equalsIgnoreCase(args.get("autoCompile"))) return args;
        boolean forced = "true".equalsIgnoreCase(args.get("autoCompile"));
        MavenCompilerSettings settings = null;
        try {
            if ("hybris".equalsIgnoreCase(platform)) throw new java.io.IOException("SAP Commerce compiler");
            if (!project.getBuild().getOutputDirectory().equals(watchDirs))
                throw new java.io.IOException("custom class output watch path");
            settings = new MavenCompilerSettings(project, session, resolver);
            String snapshot = settings.prepare(forced);
            args.put("mavenCompiler", snapshot);
            args.put("autoCompile", "true");
        } catch (Exception unsupported) {
            String reason = java.util.Objects.toString(unsupported.getMessage(), "compiler configuration unavailable");
            if (forced && settings != null && settings.canHonorUnmodeledOverride()) {
                getLog().info(reason + "; keeping explicit autoCompile=true without Maven compiler settings.");
            } else {
                args.remove("autoCompile");
                getLog().info(reason + "; Reclazz uses Maven's compile for this project" +
                        (forced ? "." : " (set autoCompile=true to override)."));
            }
        }
        return args;
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Reclazz Java agent not attached (reclazz.skip=true).");
            return;
        }
        File agentJar = findAgentJar();
        String arg = buildAgentArg(agentJar, platform, watchDirs, compilerArguments());

        String existing = project.getProperties().getProperty(propertyName);
        String expected = parseArguments(arg)[0];
        List<String> arguments = List.of(parseArguments(existing));
        List<String> configured = arguments.stream()
                .filter(value -> isReclazzAgent(value, agentJar)).toList();
        String value = existing;
        if (!configured.isEmpty()) {
            if (configured.size() != 1 || !configured.get(0).equals(expected)) {
                throw new MojoExecutionException("Conflicting Reclazz -javaagent arguments in property '"
                        + propertyName + "'. Keep one argument with the same jar path and options as "
                        + "prepare-agent, or remove the manual Reclazz argument and let the goal supply it.");
            }
            getLog().info("Reusing the existing Reclazz Java agent on property '" + propertyName + "'.");
        } else {
            value = (existing == null || existing.isBlank()) ? arg : arg + " " + existing;
            getLog().info("Reclazz Java agent set on property '" + propertyName + "': " + arg);
        }
        String lastMarker = arguments.stream()
                .filter(option -> option.equals("-Dreclazz.maven.prepareAgent") || option.startsWith("-Dreclazz.maven.prepareAgent="))
                .reduce((first, last) -> last).orElse("");
        if (!MAVEN_MARKER.equals(lastMarker)) value += " " + MAVEN_MARKER;
        String resourceProperty = "-Dreclazz.maven.resources=";
        String previous = arguments.stream().filter(a -> a.startsWith(resourceProperty))
                .reduce((first, last) -> last).orElse("");
        java.nio.file.Path manifest = null;
        try {
            manifest = MavenResourceSettings.snapshot(project, session);
        } catch (Exception unsupported) {
            getLog().debug("Maven resource layout unavailable", unsupported);
        }
        String resourceOption = resourceProperty + (manifest == null ? "" : manifest);
        if ((manifest != null || !previous.isEmpty()) && !resourceOption.equals(previous))
            value += " " + quoteArgument(resourceOption);
        project.getProperties().setProperty(propertyName, value);
    }

    /**
     * Inspect explicit argLine text using Surefire's quote/backslash rules.
     * No shell, environment or property expansion; never reserialize user text.
     * Surefire's parser is test-only; Maven does not export Plexus's CLI parser
     * to plugin realms. Tests compare this scanner with Surefire 3.5.4 directly.
     */
    String[] parseArguments(String value) throws MojoExecutionException {
        var tokens = new StringTokenizer(value == null ? "" : value.replaceAll("\\s", " "), "\"' \\", true);
        var arguments = new ArrayList<String>();
        var current = new StringBuilder();
        String quote = null;
        boolean backslash = false;
        while (tokens.hasMoreTokens()) {
            String token = tokens.nextToken();
            boolean quoteBoundary = quote == null ? token.equals("\"") || token.equals("'") : token.equals(quote);
            if (quoteBoundary) {
                if (backslash) {
                    current.append(token);
                    backslash = false;
                } else {
                    quote = quote == null ? token : null;
                }
            } else if (quote == null && token.equals(" ")) {
                if (current.length() > 0) {
                    arguments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(token);
                backslash = token.equals("\\");
            }
        }
        if (quote != null) {
            throw new MojoExecutionException("Cannot inspect JVM arguments for property '" + propertyName
                    + "': check for unbalanced quotes before running prepare-agent.");
        }
        if (current.length() > 0) arguments.add(current.toString());
        return arguments.toArray(String[]::new);
    }

    private static boolean isReclazzAgent(String argument, File expectedJar) {
        if (!argument.startsWith("-javaagent:")) return false;
        String path = argument.substring("-javaagent:".length()).split("=", 2)[0];
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return path.equals(expectedJar.getAbsolutePath())
                || name.matches("reclazz-agent(?:-[0-9][^/\\\\]*)?\\.jar");
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
        String options = joined.toString();
        if (!options.chars().allMatch(c -> c < 128)) {
            options = "reclazz-utf8:" + Base64.getUrlEncoder().withoutPadding().encodeToString(options.getBytes(StandardCharsets.UTF_8));
        }
        String argument = options.isEmpty() ? flag : flag + "=" + options;
        return quoteArgument(argument);
    }

    private static String quoteArgument(String argument) {
        if (argument.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'')) {
            return argument;
        }
        // argLine is tokenized by Maven's command-line parser. Keep this as one
        // argument; a literal double quote is a single-quoted segment between
        // double-quoted segments. Backslashes (including Windows paths) survive.
        return "\"" + argument.replace("\"", "\"'\"'\"") + "\"";
    }
}
