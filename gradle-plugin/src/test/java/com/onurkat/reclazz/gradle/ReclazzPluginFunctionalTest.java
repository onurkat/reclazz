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
