/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class PrepareAgentMojoTest {

    private final File jar = new File("/repo/reclazz-agent-1.3.0.jar");

    @Test
    void buildsFlagWithPlatformAndWatchDirs() {
        String arg = PrepareAgentMojo.buildAgentArg(jar, "spring", "/app/target/classes", null);
        assertEquals("-javaagent:" + jar.getAbsolutePath()
                + "=platform=spring,watchDirs=/app/target/classes", arg);
    }

    @Test
    void extraArgumentsAreAppended() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("verbose", "true");
        String arg = PrepareAgentMojo.buildAgentArg(jar, "spring", "/c", extra);
        assertTrue(arg.contains("platform=spring"), arg);
        assertTrue(arg.contains("watchDirs=/c"), arg);
        assertTrue(arg.endsWith("verbose=true"), arg);
    }

    @Test
    void bareFlagWhenNothingConfigured() {
        String arg = PrepareAgentMojo.buildAgentArg(jar, null, null, null);
        assertEquals("-javaagent:" + jar.getAbsolutePath(), arg);
    }
    @Test
    void quotesPathsForSurefire() throws Exception {
        for (String name : new String[] {"user space", "user's space", "user\"s space"}) {
            File spacedJar = new File(name, "reclazz-agent.jar");
            String watched = "project space/target/classes";
            String arg = PrepareAgentMojo.buildAgentArg(spacedJar, "spring", watched, null);
            var command = new org.apache.maven.surefire.shared.utils.cli.Commandline();
            command.createArg().setLine(arg);
            org.junit.jupiter.api.Assertions.assertArrayEquals(new String[] {
                    "-javaagent:" + spacedJar.getAbsolutePath() + "=platform=spring,watchDirs=" + watched
            }, command.getArguments());
        }
    }

    @Test
    void repeatedExecutionPreservesOtherAgentsAndJvmArguments() throws Exception {
        var project = new MavenProject();
        String original = "-Xmx512m  \"-javaagent:/coverage tools/jacoco.jar=destfile=a b\" "
                + "-javaagent:C:\\tools\\tracer.jar -Dnote=-javaagent:/reclazz-agent-1.0.jar";
        project.getProperties().setProperty("argLine", original);
        var mojo = mojo(project, jar, "argLine");
        mojo.execute();
        String once = project.getProperties().getProperty("argLine");
        assertEquals(PrepareAgentMojo.buildAgentArg(jar, "spring", "/classes", null) + " " + original, once);
        mojo.execute();
        assertEquals(once, project.getProperties().getProperty("argLine"));
        assertEquals(5, parsed(once).length);
    }

    @Test
    void reusesQuotedExplicitFlagWithoutRewritingCustomProperty() throws Exception {
        for (String directory : List.of("user space", "user's space", "user\"s space")) {
            File selected = new File(directory, "reclazz-agent.jar");
            var project = new MavenProject();
            var mojo = mojo(project, selected, "reclazz.agentArgs");
            String arg = PrepareAgentMojo.buildAgentArg(selected, "spring", "/classes", null);
            String existing = "  -Xmx512m\n\t" + arg + "  -Dkeep=yes";
            project.getProperties().setProperty("reclazz.agentArgs", existing);
            project.getProperties().setProperty("argLine", "untouched");
            mojo.execute();
            mojo.execute();
            assertEquals(existing, project.getProperties().getProperty("reclazz.agentArgs"));
            assertEquals("untouched", project.getProperties().getProperty("argLine"));
            assertArrayEquals(new String[] {"-Xmx512m", "-javaagent:" + selected.getAbsolutePath()
                    + "=platform=spring,watchDirs=/classes", "-Dkeep=yes"}, parsed(existing));
        }
    }

    @Test
    void conflictsStopTheGoalAndLeaveThePropertyUnchanged() throws Exception {
        String exact = PrepareAgentMojo.buildAgentArg(jar, "spring", "/classes", null);
        for (String conflict : List.of(
                exact + ",verbose=true",
                "-javaagent:/older/reclazz-agent-1.2.0.jar",
                "\"-javaagent:C:\\old tools\\reclazz-agent.jar=verbose=true\"",
                exact + " " + exact,
                exact + " -javaagent:/older/reclazz-agent-1.2.0-all.jar")) {
            var project = new MavenProject();
            String existing = "-Xmx512m " + conflict;
            project.getProperties().setProperty("customArgs", existing);
            var mojo = mojo(project, jar, "customArgs");
            var error = assertThrows(MojoExecutionException.class, mojo::execute, conflict);
            assertTrue(error.getMessage().contains("Conflicting Reclazz"), error.getMessage());
            assertTrue(error.getMessage().contains("customArgs"), error.getMessage());
            assertEquals(existing, project.getProperties().getProperty("customArgs"));
        }
    }

    @Test
    void malformedQuotesFailWithoutOverwritingAndSkipStillSkips() throws Exception {
        var project = new MavenProject();
        String existing = "\"-javaagent:/space dir/reclazz-agent.jar";
        project.getProperties().setProperty("argLine", existing);
        var mojo = mojo(project, jar, "argLine");
        assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals(existing, project.getProperties().getProperty("argLine"));
        set(mojo, "skip", true);
        mojo.execute();
        assertEquals(existing, project.getProperties().getProperty("argLine"));
    }

    @Test
    void unrelatedNamesAndOptionValuesAreNotReclazzAgents() throws Exception {
        var project = new MavenProject();
        String original = "-javaagent:/reclazz-agent-helper.jar "
                + "-javaagent:/other.jar=path=/reclazz-agent.jar -Dagent=-javaagent:/reclazz-agent.jar";
        project.getProperties().setProperty("argLine", original);
        mojo(project, jar, "argLine").execute();
        assertEquals(PrepareAgentMojo.buildAgentArg(jar, "spring", "/classes", null) + " " + original,
                project.getProperties().getProperty("argLine"));
    }

    @Test
    void explicitArgumentScannerAgreesWithSurefireOnQuotesAndBackslashes() throws Exception {
        var mojo = new PrepareAgentMojo();
        for (String input : List.of("", "''", "\"\"", "C:\\tools\\agent.jar",
                "\"-javaagent:C:\\user space\\reclazz-agent.jar=watchDirs=C:\\classes\"",
                "'-javaagent:/user space/reclazz-agent.jar'", "-Dvalue=\"a\\\"b\"",
                "-javaagent:\"/user space/reclazz-agent.jar\"=verbose=true", "-Dempty=\"\"")) {
            compareWithSurefire(mojo, input);
        }
        // Exercise interactions against the independent runtime parser, not a
        // second copy of this scanner. Includes malformed quotes and backslashes.
        char[] alphabet = {'a', ' ', '\'', '"', '\\', '\t'};
        for (int number = 0; number < 7776; number++) {
            var text = new StringBuilder();
            int n = number;
            for (int i = 0; i < 5; i++) {
                text.append(alphabet[n % alphabet.length]);
                n /= alphabet.length;
            }
            compareWithSurefire(mojo, text.toString());
        }
    }

    private static void compareWithSurefire(PrepareAgentMojo mojo, String text) throws Exception {
        String[] expected;
        try {
            expected = parsed(text);
        } catch (org.apache.maven.surefire.shared.utils.cli.CommandLineException e) {
            assertThrows(MojoExecutionException.class, () -> mojo.parseArguments(text), text);
            return;
        }
        assertArrayEquals(expected, mojo.parseArguments(text), text);
    }

    private static PrepareAgentMojo mojo(MavenProject project, File jar, String property) throws Exception {
        var artifact = new DefaultArtifact("com.onurkat.reclazz", "reclazz-agent", "1.3.0", "runtime",
                "jar", null, new DefaultArtifactHandler("jar"));
        artifact.setFile(jar);
        var mojo = new PrepareAgentMojo();
        set(mojo, "project", project);
        set(mojo, "pluginArtifacts", List.of(artifact));
        set(mojo, "propertyName", property);
        set(mojo, "platform", "spring");
        set(mojo, "watchDirs", "/classes");
        return mojo;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String[] parsed(String line) throws Exception {
        var command = new org.apache.maven.surefire.shared.utils.cli.Commandline();
        // Surefire DefaultForkConfiguration normalizes whitespace before setLine.
        command.createArg().setLine(line.replaceAll("\\s", " "));
        return command.getArguments();
    }

}
