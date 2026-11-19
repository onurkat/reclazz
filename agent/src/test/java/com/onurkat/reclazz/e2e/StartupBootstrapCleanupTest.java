/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StartupBootstrapCleanupTest {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void startupReportsRemovedCopiesToConsoleAndStatusListeners(boolean verbose) throws Exception {
        Path jar = Path.of(WatchedApp.agentJarOrSkip());
        Path platform = Files.createDirectories(root.resolve("bin/platform"));
        Path extension = Files.createDirectories(root.resolve("bin/custom/example"));
        Path classes = Files.createDirectories(extension.resolve("classes"));
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/localextensions.xml"),
                "<hybrisconfig><extensions><extension name='example'/></extensions></hybrisconfig>");
        Files.writeString(extension.resolve("extensioninfo.xml"),
                "<extensioninfo><extension name='example'/></extensioninfo>");
        List<String> compile = new ArrayList<>(List.of("-g:source", "-d", classes.toString()));
        for (String name : List.of("Dto", "OtherDto")) {
            Path file = platform.resolve("bootstrap/gensrc/com/example/" + name + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package com.example; public class " + name + " {}");
            compile.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compile.toArray(String[]::new)));
        Path source = root.resolve("App.java"), appClasses = Files.createDirectories(root.resolve("app-classes"));
        Files.writeString(source, "public class App { public static void main(String[] args) { System.out.println(\"STARTUP_READY\"); System.exit(0); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", appClasses.toString(), source.toString()));
        Path log = root.resolve("child.log"), session = root.resolve("session.log");
        List<String> command = new ArrayList<>(WatchedApp.utf8Java(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + jar + "=platform=hybris,hybrisHome=" + root
                        + ",autoCompile=true,startupDelaySec=0,verbose=" + verbose + ",sessionLog=" + session,
                "-cp", appClasses.toString(), "App"));
        Process child = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, child.exitValue(), () -> read(log));
            String output = read(log), events = read(session);
            assertTrue(output.contains("STARTUP_READY"), output);
            assertFalse(Files.exists(classes.resolve("com/example/Dto.class")), output);
            assertFalse(Files.exists(classes.resolve("com/example/OtherDto.class")), output);
            String summary = "Removed 2 bootstrap-generated class copies from extension output";
            assertEquals(1, output.lines().filter(line -> line.contains(summary) && line.contains("[INFO]")).count(), output);
            assertEquals(1, events.lines().filter(line -> line.contains(summary) && line.contains("INFO")).count(), events);
            assertEquals(verbose ? 2 : 0, output.lines().filter(line -> line.contains("Removed bootstrap-generated extension copy:")).count(), output);
        } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
    }
    private static String read(Path file) {
        try { return Files.readString(file); } catch (Exception failure) { return failure.toString(); }
    }
}
