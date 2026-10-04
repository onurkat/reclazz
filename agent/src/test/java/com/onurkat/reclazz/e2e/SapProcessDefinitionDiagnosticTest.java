/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SapProcessDefinitionDiagnosticTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void registeredEditsAndDeletesWarnWithoutReloadingAndUnrelatedXmlStaysIgnored(boolean outside) throws Exception {
        Path directory = Files.createDirectories(tmp.resolve(outside ? "extra" : "classes"));
        Path process = directory.resolve("flow.xml"), other = directory.resolve("other.xml");
        Files.writeString(process, "<process name='before'/>");
        Files.writeString(other, "<process name='other'/>");
        String contract = Files.readString(Path.of("src/test/java/de/hybris/platform/processengine/definition/ProcessDefinitionResource.java"));
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .with("ProcessDefinitionResource", contract).with("App", APP)
                .jvmArgs("-Dprocess.file=" + process).start()) {
            app.awaitOrFail("READY", "application missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            Files.writeString(other, "<process name='unrelated-change'/>");
            Files.writeString(process, "<process name='after'/>");
            app.awaitOrFail("SAP process definition flow.xml MODIFIED: not applied; restart required", "changed definition was silently ignored");
            Files.delete(process);
            app.awaitOrFail("SAP process definition flow.xml DELETED: not applied; restart required", "deletion was silently ignored");
            assertTrue(app.output().stream().anyMatch(s -> s.contains("[WARN]") && s.contains("SAP process definition")), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("other.xml")), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Spring XML") || s.contains("[SWAP]")
                    || s.contains("Reloaded ") || s.contains("Structural reload:")), app.tail());
        }
    }
    private static final String APP = """
            package app;
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new org.springframework.context.support.GenericApplicationContext();
                    var resource = new de.hybris.platform.processengine.definition.ProcessDefinitionResource();
                    resource.setResource(new org.springframework.core.io.FileSystemResource(System.getProperty("process.file")));
                    context.getBeanFactory().registerSingleton("process", resource);
                    context.refresh();
                    System.out.println("READY");
                    Thread.sleep(120000);
                }
            }
            """;
}
