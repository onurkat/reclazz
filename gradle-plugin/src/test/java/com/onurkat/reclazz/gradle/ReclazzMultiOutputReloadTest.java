/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.gradle;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ReclazzMultiOutputReloadTest {
    @TempDir Path projectDir;

    @Test
    void generatedArgumentReloadsRetainedObjectsFromBothMainOutputs() throws Exception {
        Path first = Files.createDirectories(projectDir.resolve("first output"));
        Path second = Files.createDirectories(projectDir.resolve("second output"));
        String classpath = first + File.pathSeparator + second;
        compile("First", unit("First", 1), first, classpath);
        compile("Second", unit("Second", 1), second, classpath);
        compile("Main", """
                package fixture;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        First a = new First(); Second b = new Second();
                        long end = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
                        while (System.nanoTime() < end) {
                            System.out.println("VALUES=" + a.value() + ":" + b.value()
                                    + " PID=" + ProcessHandle.current().pid());
                            Thread.sleep(100);
                        }
                    }
                }
                """, first, classpath);

        String agent = System.getProperty("reclazz.agent.jar");
        assertNotNull(agent, "test task must supply the built agent");
        assertTrue(Files.isRegularFile(Path.of(agent)), agent);
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'live-outputs'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'java'; id 'com.onurkat.reclazz' }
                reclazz {
                    agentJar = file('%s')
                    arguments.put('startupDelaySec', '1')
                    arguments.put('debounceMs', '100')
                }
                sourceSets.main.output.classesDirs.setFrom(files('first output', 'second output'))
                tasks.register('bootRun', JavaExec) {
                    mainClass = 'fixture.Main'
                    classpath = sourceSets.main.runtimeClasspath
                }
                tasks.register('captureAgentArgument') {
                    doLast {
                        def flags = tasks.named('bootRun').get().jvmArgumentProviders.collectMany {
                            it.asArguments().toList()
                        }
                        assert flags.size() == 1
                        file('agent-argument.txt').text = flags[0]
                    }
                }
                """.formatted(agent.replace("\\", "\\\\").replace("'", "\\'")));
        GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                .withArguments("captureAgentArgument", "-q", "--stacktrace").build();
        // Use the provider's actual flag verbatim; never repair watchDirs in this fixture.
        String flag = Files.readString(projectDir.resolve("agent-argument.txt"));
        Path log = projectDir.resolve("application.log");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process app = new ProcessBuilder(java, flag, "-cp", classpath, "fixture.Main")
                .directory(projectDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            String pid = " PID=" + app.pid();
            await(app, log, "VALUES=1:1" + pid);
            await(app, log, "] Watching ");
            compile("First", unit("First", 2), first, classpath);
            await(app, log, "VALUES=2:1" + pid);
            compile("Second", unit("Second", 2), second, classpath);
            await(app, log, "VALUES=2:2" + pid);
        } finally {
            app.destroy();
            if (!app.waitFor(10, TimeUnit.SECONDS)) {
                app.destroyForcibly();
                assertTrue(app.waitFor(10, TimeUnit.SECONDS), "fixture JVM did not stop");
            }
        }
    }

    private void compile(String name, String source, Path output, String classpath) throws Exception {
        Path file = projectDir.resolve(name + ".java");
        Files.writeString(file, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required");
        assertEquals(0, compiler.run(null, null, null, "--release", "17", "-classpath", classpath,
                "-d", output.toString(), file.toString()), "fixture compilation failed");
    }

    private static String unit(String name, int value) {
        return "package fixture; public class " + name + " { public int value() { return " + value + "; } }";
    }

    private static void await(Process app, Path log, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String output = "";
        while (System.nanoTime() < deadline) {
            output = Files.readString(log);
            if (output.contains(expected)) return;
            assertTrue(app.isAlive(), "fixture exited before " + expected + "\n" + output);
            Thread.sleep(50);
        }
        fail("Missing " + expected + "\n" + output);
    }
}
