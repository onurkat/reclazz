/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class ReclazzPluginFunctionalTest {

    @TempDir
    Path projectDir;

    @Test
    void wiresJavaagentIntoBootRun() throws IOException {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("agent.jar", "not a real jar, only a path for the flag");
        write("build.gradle",
                "plugins { id 'java'; id 'com.onurkat.reclazz' }\n"
                + "reclazz {\n"
                + "  agentJar = file('agent.jar')\n"
                + "  arguments.put('verbose', 'true')\n"
                + "}\n"
                + "tasks.register('bootRun', JavaExec) { mainClass = 'Main'; classpath = files() }\n"
                + "tasks.register('printReclazz') {\n"
                + "  doLast {\n"
                + "    def t = tasks.named('bootRun', JavaExec).get()\n"
                + "    t.jvmArgumentProviders.each { p -> println 'FLAG:' + p.asArguments().join(' ') }\n"
                + "  }\n"
                + "}\n");

        BuildResult result = run("printReclazz");
        String out = result.getOutput();

        assertTrue(out.contains("FLAG:-javaagent:"), out);
        assertTrue(out.contains("agent.jar="), out);
        assertTrue(out.contains("verbose=true"), out);
        // watchDirs defaults to the main source set output when not set.
        assertTrue(out.contains("watchDirs="), out);
    }

    @Test
    void addsNothingWhenDisabled() throws IOException {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("agent.jar", "path only");
        write("build.gradle",
                "plugins { id 'java'; id 'com.onurkat.reclazz' }\n"
                + "reclazz {\n"
                + "  enabled = false\n"
                + "  agentJar = file('agent.jar')\n"
                + "}\n"
                + "tasks.register('bootRun', JavaExec) { mainClass = 'Main'; classpath = files() }\n"
                + "tasks.register('printReclazz') {\n"
                + "  doLast {\n"
                + "    def t = tasks.named('bootRun', JavaExec).get()\n"
                + "    t.jvmArgumentProviders.each { p -> println 'FLAG:' + p.asArguments().join(' ') }\n"
                + "  }\n"
                + "}\n");

        BuildResult result = run("printReclazz");
        assertFalse(result.getOutput().contains("-javaagent:"), result.getOutput());
    }

    @Test
    void reclazzStatusReportsNotAttachedWithNoAgent() throws IOException {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("build.gradle", "plugins { id 'java'; id 'com.onurkat.reclazz' }\n");

        BuildResult result = run("reclazzStatus");
        assertTrue(result.getOutput().contains("\"attached\":false"), result.getOutput());
    }

    @Test
    void defaultsToPackagedVersionInsteadOfConsumerVersion() throws Exception {
        assertDefaultVersion("version = '0.0.1-SNAPSHOT'\n");
    }

    @Test
    void defaultsToPackagedVersionWithoutConsumerVersion() throws Exception {
        assertDefaultVersion("");
    }

    private void assertDefaultVersion(String consumerVersion) throws Exception {
        String version = System.getProperty("reclazz.plugin.version");
        write("settings.gradle", "rootProject.name = 'sample'");
        Path artifact = projectDir.resolve("repo/com/onurkat/reclazz/reclazz-agent/" + version);
        Files.createDirectories(artifact);
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(
                artifact.resolve("reclazz-agent-" + version + ".jar")))) { }
        write("repo/com/onurkat/reclazz/reclazz-agent/" + version + "/reclazz-agent-" + version + ".pom",
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.onurkat.reclazz</groupId>"
                + "<artifactId>reclazz-agent</artifactId><version>" + version + "</version></project>");
        write("build.gradle", "plugins { id 'java'; id 'com.onurkat.reclazz' }\n"
                + consumerVersion
                + "repositories { maven { url = uri('repo') } }\n"
                + "tasks.register('resolveAgent') { doLast { println 'AGENT:' + configurations.reclazzAgent.singleFile.name } }\n");
        String out = run("resolveAgent").getOutput();
        assertTrue(out.contains("AGENT:reclazz-agent-" + version + ".jar"), out);
    }

    @Test
    void explicitAgentVersionOverridesDefault() throws Exception {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("build.gradle", "plugins { id 'java'; id 'com.onurkat.reclazz' }\n"
                + "reclazz { agentVersion = '7.8.9' }\n"
                + "tasks.register('versionCheck') { doLast { println 'VERSION:' + reclazz.agentVersion.get() } }\n");
        assertTrue(run("versionCheck").getOutput().contains("VERSION:7.8.9"));
    }

    @Test
    void disabledBootRunDoesNotResolveAgent() throws Exception {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("src/main/java/Main.java", "public class Main { public static void main(String[] args) {"
                + "System.out.println(\"APPLICATION_RAN\"); } }");
        write("build.gradle", "plugins { id 'java'; id 'com.onurkat.reclazz' }\n"
                + "reclazz { enabled = false; agentVersion = 'does-not-exist' }\n"
                + "tasks.register('bootRun', JavaExec) { mainClass = 'Main'; classpath = sourceSets.main.runtimeClasspath }\n");
        assertTrue(run("bootRun").getOutput().contains("APPLICATION_RAN"));
    }

    @Test
    void safeBuildRequiresExplicitConfiguration() throws Exception {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("build.gradle", "plugins { id 'com.onurkat.reclazz' }\n");
        String out = GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                .withArguments("reclazzSafeBuild", "--stacktrace").buildAndFail().getOutput();
        assertTrue(out.contains("Configure mcpJar"), out);
    }

    @Test
    void mixedSafetyTaskGraphFailsBeforeAnyOutputTask() throws Exception {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("build.gradle", "plugins { id 'com.onurkat.reclazz' }\n"
                + "tasks.register('unsafeWrite') { doLast { file('wrote').text = 'bad' } }\n"
                + "tasks.named('reclazzSafeBuild') { dependsOn 'unsafeWrite' }\n");
        String out = GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                .withArguments("reclazzSafeBuild", "--stacktrace").buildAndFail().getOutput();
        assertTrue(out.contains("Invoke reclazzSafeBuild alone"), out);
        assertFalse(Files.exists(projectDir.resolve("wrote")));
    }

    @Test
    void continuousAndConfigurationCacheCannotBypassOuterGraphGuard() throws Exception {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("build.gradle", "plugins { id 'com.onurkat.reclazz' }\n");
        for (String flag : java.util.List.of("--configuration-cache", "--continuous")) {
            String out = GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                    .withArguments("reclazzSafeBuild", flag, "--stacktrace").buildAndFail().getOutput();
            assertTrue(out.contains("Invoke reclazzSafeBuild alone"), out);
        }
    }

    @Test
    void nestedSafeBuildIsRefusedBeforeAnyAgentConnection() throws Exception {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("build.gradle", "plugins { id 'com.onurkat.reclazz' }\n");
        var environment = new java.util.HashMap<>(System.getenv());
        environment.put("RECLAZZ_SAFE_BUILD_ACTIVE", "1");
        String out = GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                .withEnvironment(environment).withArguments("reclazzSafeBuild", "--stacktrace")
                .buildAndFail().getOutput();
        assertTrue(out.contains("Nested Reclazz safe build refused"), out);
    }

    @Test
    void ordinaryCompilationStillReusesConfigurationCacheWithoutSafetyConfiguration() throws Exception {
        write("settings.gradle", "rootProject.name = 'sample'");
        write("build.gradle", "plugins { id 'java'; id 'com.onurkat.reclazz' }\n");
        write("src/main/java/Main.java", "public class Main {}\n");
        GradleRunner runner = GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                .withArguments("compileJava", "--configuration-cache", "--stacktrace");
        runner.build();
        String out = runner.build().getOutput();
        assertTrue(out.contains("Reusing configuration cache."), out);
        assertTrue(Files.exists(projectDir.resolve("build/classes/java/main/Main.class")));
    }

    private BuildResult run(String task) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(task, "-q", "--stacktrace")
                .build();
    }

    private void write(String path, String content) throws IOException {
        File file = projectDir.resolve(path).toFile();
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }
}
