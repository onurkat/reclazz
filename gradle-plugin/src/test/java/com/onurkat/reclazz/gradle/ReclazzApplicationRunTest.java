/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ReclazzApplicationRunTest {
    @TempDir Path projectDir;

    @Test
    void applicationRunIsOffByDefaultWithoutResolvingAgent() throws Exception {
        plain("", "id 'application'; id 'com.onurkat.reclazz'");
        String out = run("run").getOutput();
        assertTrue(out.contains("MARKER=kept"), out);
        assertFalse(out.contains("-javaagent:"), out);
    }

    @Test
    void explicitOptOutIsLazyAfterEarlyTaskRealization() throws Exception {
        plain("applyToRun = false", "id 'com.onurkat.reclazz'; id 'application'");
        assertFalse(run("run").getOutput().contains("-javaagent:"));
    }

    @Test
    void globalDisableDoesNotResolveAgentEvenWhenRunOptedIn() throws Exception {
        plain("applyToRun = true; enabled = false", "id 'application'; id 'com.onurkat.reclazz'");
        String out = run("run").getOutput();
        assertTrue(out.contains("MARKER=kept"), out);
        assertFalse(out.contains("-javaagent:"), out);
    }

    @Test
    void eitherPluginOrderUsesOneExplicitJarAndDoesNotForceSpring() throws Exception {
        for (String plugins : new String[]{"id 'application'; id 'com.onurkat.reclazz'",
                "id 'com.onurkat.reclazz'; id 'application'"}) {
            plain("applyToRun = true; agentJar = file('agent with spaces.jar')", plugins);
            write("agent with spaces.jar", "path only");
            Files.writeString(projectDir.resolve("build.gradle"), """
                    tasks.register('flags') { doLast {
                        def flags = tasks.run.jvmArgumentProviders.collectMany { it.asArguments().toList() }
                        assert flags.size() == 1
                        println 'FLAG:' + flags[0]
                        assert application.applicationDefaultJvmArgs == ['-Ddemo.marker=kept']
                    } }
                    """, java.nio.file.StandardOpenOption.APPEND);
            String out = run("flags").getOutput();
            assertTrue(out.contains("agent with spaces.jar=watchDirs="), out);
            assertFalse(out.contains("platform="), out);
        }
    }

    @Test
    void unrelatedJavaExecAndPackagedLaunchersRemainUninstrumented() throws Exception {
        plain("applyToRun = true; agentJar = file('agent.jar')", "id 'application'; id 'com.onurkat.reclazz'");
        write("agent.jar", "path only: the unrelated task must not load this");
        Files.writeString(projectDir.resolve("build.gradle"), """
                tasks.register('other', JavaExec) {
                    mainClass = 'Main'
                    classpath = sourceSets.main.runtimeClasspath
                    jvmArgs '-Ddemo.marker=kept'
                }
                """, java.nio.file.StandardOpenOption.APPEND);
        String out = run("other", "startScripts").getOutput();
        assertTrue(out.contains("MARKER=kept"), out);
        assertFalse(out.contains("-javaagent:"), out);
        for (String name : new String[]{"plain", "plain.bat"}) {
            String script = Files.readString(projectDir.resolve("build/scripts/" + name));
            assertFalse(script.contains("-javaagent:"), script);
            assertTrue(script.contains("demo.marker=kept"), script);
        }
    }

    @Test
    void runNamedTaskWithoutApplicationPluginIsNotSelected() throws Exception {
        write("settings.gradle", "rootProject.name = 'plain'");
        write("src/main/java/Main.java", simpleMain());
        write("build.gradle", """
                plugins { id 'java'; id 'com.onurkat.reclazz' }
                reclazz { applyToRun = true; agentVersion = 'does-not-exist' }
                tasks.register('run', JavaExec) {
                    mainClass = 'Main'; classpath = sourceSets.main.runtimeClasspath
                    jvmArgs '-Ddemo.marker=kept'
                }
                """);
        String out = run("run").getOutput();
        assertTrue(out.contains("MARKER=kept"), out);
        assertFalse(out.contains("-javaagent:"), out);
    }

    @Test
    void realApplicationRunReloadsRetainedObjectInSameJvm() throws Exception {
        String agent = System.getProperty("reclazz.agent.jar");
        assertNotNull(agent);
        assertTrue(Files.isRegularFile(Path.of(agent)), agent);
        plain("applyToRun = true; agentJar = file('" + agent.replace("\\", "\\\\").replace("'", "\\'")
                + "'); arguments.put('startupDelaySec', '1'); arguments.put('debounceMs', '100')",
                "id 'com.onurkat.reclazz'; id 'application'");
        write("src/main/java/Value.java", value(1));
        write("src/main/java/Main.java", """
                public class Main {
                    public static void main(String[] args) throws Exception {
                        long pid = ProcessHandle.current().pid();
                        java.nio.file.Files.writeString(java.nio.file.Path.of("app.pid"), Long.toString(pid));
                        System.out.println("ARGS=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                        Value retained = new Value();
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
                        while (System.nanoTime() < deadline && !java.nio.file.Files.exists(java.nio.file.Path.of("stop"))) {
                            int value = retained.get();
                            System.out.println("VALUE=" + value + " PID=" + pid + " MARKER=" + System.getProperty("demo.marker"));
                            if (value == 2) return;
                            Thread.sleep(100);
                        }
                    }
                }
                """);
        StringWriter output = new StringWriter();
        GradleRunner runner = runner("run").forwardStdOutput(output).forwardStdError(output);
        var executor = Executors.newSingleThreadExecutor();
        Future<BuildResult> running = executor.submit(runner::build);
        try {
            await(running, output, "VALUE=1 PID=");
            String pid = Files.readString(projectDir.resolve("app.pid"));
            await(running, output, "] Watching ");
            write("src/main/java/Value.java", value(2));
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler);
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-d",
                    projectDir.resolve("build/classes/java/main").toString(),
                    projectDir.resolve("src/main/java/Value.java").toString()));
            await(running, output, "VALUE=2 PID=" + pid + " MARKER=kept");
            String completed = running.get(30, TimeUnit.SECONDS).getOutput();
            assertTrue(completed.contains("ARGS=[") && completed.contains("-javaagent:"), completed);
            assertFalse(completed.contains("platform=spring"), completed);
            completed.lines().filter(line -> line.startsWith("ARGS=") || line.startsWith("VALUE="))
                    .distinct().forEach(System.out::println);
        } finally {
            // Signal our fixture, not a shared Gradle daemon. Its own deadline also bounds failures.
            write("stop", "stop");
            try {
                running.get(150, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                Files.writeString(projectDir.resolve("live-run.log"), output.toString());
            }
        }
    }

    private static String value(int n) {
        return "public class Value { public int get() { return " + n + "; } }";
    }

    private static void await(Future<BuildResult> running, StringWriter output, String token) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (output.toString().contains(token)) return;
            if (running.isDone()) fail("run exited before " + token + "\n" + running.get().getOutput());
            Thread.sleep(50);
        }
        fail("Missing " + token + "\n" + output);
    }

    private void plain(String config, String plugins) throws Exception {
        write("settings.gradle", "rootProject.name = 'plain'");
        write("src/main/java/Main.java", simpleMain());
        write("build.gradle", "plugins { " + plugins + " }\n"
                + "application { mainClass = 'Main'; applicationDefaultJvmArgs = ['-Ddemo.marker=kept'] }\n"
                + "tasks.named('run').get() // force early realization before the opt-in is configured\n"
                + "reclazz { agentVersion = 'does-not-exist'; " + config + " }\n");
    }

    private static String simpleMain() {
        return "public class Main { public static void main(String[] args) {"
                + "System.out.println(\"MARKER=\" + System.getProperty(\"demo.marker\"));"
                + "System.out.println(\"ARGS=\" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()); } }";
    }

    private GradleRunner runner(String... tasks) {
        var args = new java.util.ArrayList<>(java.util.List.of(tasks));
        args.add("--offline"); args.add("--stacktrace");
        return GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath().withArguments(args);
    }

    private BuildResult run(String... tasks) { return runner(tasks).build(); }

    private void write(String file, String text) throws Exception {
        Path path = projectDir.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
