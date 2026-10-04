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
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SapIndexingGuidanceTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"FieldValueProvider", "ValueResolver", "TypeValueResolver"})
    void codeChangesDoNotRewriteStoredDocuments(String contract) throws Exception {
        Path reindex = tmp.resolve("reindex");
        String provider = "package app; public class Provider implements de.hybris.platform.solrfacetsearch.provider."
                + contract + " { public String value() { return \"v1\"; } }";
        String ordinary = "package app; public class Ordinary { public String value() { return \"plain1\"; } }";
        // Identity-only portable fixture. Actual SDK signatures/documents are tested by the SDK runner.
        String marker = "package de.hybris.platform.solrfacetsearch.provider; public interface " + contract + " {}";
        try (var app = WatchedApp.in(tmp).with(contract, marker).with("Provider", provider)
                .with("Ordinary", ordinary).with("App", APP).jvmArgs("-Dreindex.file=" + reindex).start()) {
            app.awaitOrFail("live=v1;stored=v1;ordinary=plain1", "initial snapshot missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            app.rewriteAll(Map.of("Provider", provider.replace("v1", "v2"),
                    "Ordinary", ordinary.replace("plain1", "plain2")));
            app.awaitOrFail("live=v2;stored=v1;ordinary=plain2", "code and stored document must be separate");
            assertTrue(app.awaits("SAP indexing code app.Provider reloaded", 10), app.tail());
            String guidance = app.latest("SAP indexing code app.Provider reloaded");
            assertTrue(guidance.contains("[INFO]") && guidance.contains("Reclazz did not reindex stored documents"), guidance);
            assertFalse(app.output().stream().anyMatch(s -> s.contains("SAP indexing code app.Ordinary")), app.tail());
            Files.writeString(reindex, "explicit");
            app.awaitOrFail("live=v2;stored=v2;ordinary=plain2", "explicit reindex did not update snapshot");
            long before = app.output().stream().filter(s -> s.contains("SAP indexing code app.Provider reloaded")).count();
            app.rewrite("Provider", provider.replace("v1", "v3"));
            app.awaitOrFail("live=v3;stored=v2;ordinary=plain2", "later reload rewrote stored document");
            app.awaitOrFail("Reloaded app.Provider", "single reload missing");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (app.output().stream().filter(s -> s.contains("SAP indexing code app.Provider reloaded")).count() == before
                    && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(before + 1, app.output().stream().filter(s -> s.contains("SAP indexing code app.Provider reloaded")).count(), app.tail());
        }
    }

    private static final String APP = """
            package app;
            public class App {
                public static void main(String[] args) throws Exception {
                    var provider = new Provider(); var ordinary = new Ordinary();
                    String stored = provider.value();
                    var trigger = java.nio.file.Path.of(System.getProperty("reindex.file"));
                    while (true) {
                        if (java.nio.file.Files.deleteIfExists(trigger)) stored = provider.value();
                        System.out.println("live=" + provider.value() + ";stored=" + stored + ";ordinary=" + ordinary.value());
                        Thread.sleep(100);
                    }
                }
            }
            """;
}
