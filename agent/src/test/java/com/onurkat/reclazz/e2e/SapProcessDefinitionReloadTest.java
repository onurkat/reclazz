/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real dispatcher/watcher and module; synthetic native contracts. SDK lane tests native advice. */
class SapProcessDefinitionReloadTest {
    @TempDir Path temporary;
    @Test void watcherPublishesNewGraphPreservesOldVersionAndRecoversAfterBrokenSave() throws Exception {
        Files.createDirectories(temporary.resolve("config"));
        Files.createDirectories(temporary.resolve("bin/platform"));
        Files.writeString(temporary.resolve("config/localextensions.xml"),
                "<hybrisconfig><extensions><extension name='example'/></extensions></hybrisconfig>");
        Path extension = Files.createDirectories(temporary.resolve("bin/custom/example"));
        Files.writeString(extension.resolve("extensioninfo.xml"),
                "<extensioninfo><extension name='example'/></extensioninfo>");
        Path file = Files.createDirectories(extension.resolve("resources")).resolve("flow.xml");
        Files.writeString(file, "<process name='exampleA' start='v1'/>");
        var builder = WatchedApp.in(temporary).classpath(WatchedApp.springClasspath() + File.pathSeparator
                + WatchedApp.agentJarOrSkip()).agentArgs("platform=hybris,hybrisHome=" + temporary
                + ",startupDelaySec=0,debounceMs=100").jvmArgs("-Dexample.process.file=" + file);
        for (String name : List.of("ProcessDefinitionResource", "ProcessDefinitionId", "ProcessDefinition",
                "ProcessDefinitionFactory", "DefaultProcessDefinitionFactory", "ProcessDefinitionsCache",
                "ProcessDefinitionsProvider", "XMLProcessDefinitionsReader")) {
            String source = Files.readString(Path.of("src/test/java/de/hybris/platform/processengine/definition/" + name + ".java"));
            // In this child JVM the real reader transformer supplies parsed(); avoid double recording.
            if (name.equals("ProcessDefinitionsProvider")) source = source.replace(
                    "com.onurkat.reclazz.platform.SapProcessDefinitions.parsed(holder.getResource(), parsed);", "");
            builder.with(name, source);
        }
        for (String path : List.of("com/onurkat/reclazz/hybris/ProcessTenantFixture.java", "de/hybris/platform/core/Registry.java", "de/hybris/platform/jalo/JaloSession.java"))
            builder.with(Path.of(path).getFileName().toString().replace(".java", ""), Files.readString(Path.of((path.startsWith("de/") ? "src/processTestFixtures/java/" : "src/test/java/") + path)));
        try (var app = builder.with("App", APP).start()) {
            app.awaitOrFail("PROCESS_READY", "native fixture did not load");
            app.awaitOrFail("] Watching ", "watcher missing");
            Files.writeString(file, "<process name='exampleA' start='v2'/>");
            app.awaitOrFail("SAP process exampleA reloaded from flow.xml", "registered save did not reload");
            app.awaitOrFail("PROCESS_V2_WITH_V1_RETAINED", "active graph/history contract failed");
            Files.writeString(file, "<process>");
            app.awaitOrFail("native process validation failed", "malformed save was not refused");
            Files.writeString(file, "<process name='exampleA' start='v3'/>");
            app.awaitOrFail("PROCESS_V3_RECOVERED", "valid save did not recover");
            assertFalse(app.output().stream().anyMatch(s -> s.contains("ClassNotFoundException")
                    || s.contains("VerifyError") || s.contains("[ ERR]")), app.tail());
        }
    }
    private static final String APP = """
            package app;
            import de.hybris.platform.processengine.definition.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new org.springframework.context.support.GenericApplicationContext();
                    var file = java.nio.file.Path.of(System.getProperty("example.process.file"));
                    var resource = new ProcessDefinitionResource();
                    resource.setResource(new org.springframework.core.io.FileSystemResource(file));
                    var reader = new XMLProcessDefinitionsReader();
                    var provider = new ProcessDefinitionsProvider(); provider.reader = reader; provider.applicationContext = context;
                    var cache = new ProcessDefinitionsCache(); cache.processDefinitionsProvider = provider;
                    var factory = new DefaultProcessDefinitionFactory(); factory.xmlDefinitionsReader = reader; factory.definitionsCache = cache;
                    context.getBeanFactory().registerSingleton("resource", resource);
                    context.getBeanFactory().registerSingleton("factory", factory);
                    context.refresh(); com.onurkat.reclazz.hybris.ProcessTenantFixture.attach(context); cache.initialise(true);
                    var original = cache.definitions.get(new ProcessDefinitionId("exampleA"));
                    System.out.println("PROCESS_READY"); boolean v2 = false;
                    for (int i = 0; i < 2400; i++) {
                        cache.definitionsLock.readLock().lock();
                        try {
                            var active = cache.definitions.get(new ProcessDefinitionId("exampleA"));
                            if (!v2 && active.graph().equals("v2")) {
                                if (cache.definitions.get(original.getId()) != original) throw new AssertionError("Old version lost");
                                v2 = true; System.out.println("PROCESS_V2_WITH_V1_RETAINED");
                            }
                            if (v2 && active.graph().equals("v3")) { System.out.println("PROCESS_V3_RECOVERED"); break; }
                        } finally { cache.definitionsLock.readLock().unlock(); }
                        Thread.sleep(50);
                    }
                    // Keep the observed application alive until WatchedApp closes its child JVM.
                    Thread.sleep(120000);
                    context.close();
                }
            }
            """;
}
