/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.transform.TransformTestBase;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class PremiumJarIsolationTest extends TransformTestBase {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(strings = {"core", "full"})
    void physicalJarKeepsFreeSapLifecycleWithoutTestClasspathLeakage(String variant) throws Exception {
        Path jar = Path.of(System.getProperty(variant.equals("core") ? "reclazz.core.agent.jar" : "reclazz.agent.jar"));
        assertTrue(Files.isRegularFile(jar));
        Path model = tmp.resolve("bin/platform/bootstrap/modelclasses/com/example/ManualModel.class");
        Files.createDirectories(model.getParent());
        Files.write(model, compile(new SourceFile("com.example.ManualModel", model("before", ""))).get("com/example/ManualModel"));
        Files.write(tmp.resolve("saved-model.bin"), compile(new SourceFile("com.example.ManualModel",
                model("after", "public int added() { return 21; }"))).get("com/example/ManualModel"));
        Files.createDirectories(tmp.resolve("config"));
        Files.writeString(tmp.resolve("config/localextensions.xml"),
                "<hybrisconfig><extensions><extension name='core'/><extension name='web'/></extensions></hybrisconfig>");
        Files.writeString(tmp.resolve("config/local.properties"), "example=before\n");
        for (String name : List.of("core", "web")) {
            Path ext = Files.createDirectories(tmp.resolve("bin/custom/" + name));
            Files.createDirectories(ext.resolve("classes"));
            Files.writeString(ext.resolve("extensioninfo.xml"), "<extensioninfo><extension name='" + name + "'>"
                    + (name.equals("web") ? "<requires-extension name='core'/>" : "") + "</extension></extensioninfo>");
        }
        source("bin/custom/core/src/com/example/Core.java", "package com.example; public class Core { public int value() { return 7; } }");
        source("bin/custom/web/web/src/com/example/Web.java", "package com.example; public class Web { public int value() { return new Core().value(); } }");
        for (String name : List.of("example-items.xml", "data.impex", "localization/example-locales_en.properties", "example-backoffice-config.xml"))
            source("bin/custom/core/resources/" + name, "initial=before\n");
        Path fixture = tmp.resolve("PhysicalAgentProbe.java");
        try (var in = getClass().getResourceAsStream("/premium-isolation/PhysicalAgentProbe.java")) {
            assertNotNull(in); Files.write(fixture, in.readAllBytes());
        }
        Path app = Files.createDirectories(tmp.resolve("probe"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", jar.toString(),
                "-d", app.toString(), fixture.toString()), "fixture must compile against the selected jar only");
        Path log = tmp.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-javaagent:" + jar + "=platform=hybris,hybrisHome=" + tmp + ",startupDelaySec=0,debounceMs=100,autoImpex=true",
                "-cp", jar + java.io.File.pathSeparator + app, "com.example.PhysicalAgentProbe", tmp.toString(), variant)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (child.isAlive() && System.nanoTime() < end) {
                String output = Files.readString(log);
                if (output.contains("Watching ") && output.contains("PROBE_MAIN_READY")) {
                    Files.writeString(tmp.resolve("watcher-ready"), "ready"); break;
                }
                Thread.sleep(50);
            }
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, child.exitValue(), () -> read(log));
            String output = read(log);
            assertTrue(output.contains("PHYSICAL_" + variant.toUpperCase(Locale.ROOT) + "_OK"), output);
            assertFalse(output.contains("ClassNotFoundException"), output);
            assertFalse(output.contains("NoClassDefFoundError"), output);
            assertEquals(variant.equals("core") ? 1 : 0, output.lines()
                    .filter(line -> line.contains("Optional platform automation is absent;")).count(), output);
        } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
    }
    private void source(String path, String body) throws Exception {
        Path file = tmp.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, body);
    }
    private static String model(String value, String added) {
        return "package com.example; public class ManualModel { public String run() { return \"" + value + "\"; } " + added + " }";
    }
    private static String read(Path file) { try { return Files.readString(file); } catch (Exception e) { return e.toString(); } }
}
