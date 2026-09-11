/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A property change re-evaluates a mixed text and #{...} template @Value, and an
 *  uncheckable embedded expression leaves every old value in place. */
class ComputedValueTemplateReloadTest {
    @TempDir Path tmp;

    @Test
    void templateValuesFollowPropertyChangesAndHoldOnUncheckable() throws Exception {
        Path properties = Files.createDirectories(tmp.resolve("classes")).resolve("application.properties");
        Files.writeString(properties, content("8", "1", "2", "old"));
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("CFG_READY=host:9|[1/2]|old", "Spring must evaluate the initial templates");
            app.awaitOrFail("] Watching 1 director", "watcher did not start");

            Files.writeString(properties, content("10", "3", "4", "new"));
            app.awaitOrFail("Applied 4 property changes", "valid candidate was not applied");
            probe(app, 1, "host:11|[3/4]|new");

            // A placeholder that resolves to a bean/type call makes an embedded
            // expression uncheckable: the whole save is held, values stay.
            Files.writeString(properties, content("10", "T(java.lang.System).lineSeparator()", "4", "rejected"));
            app.awaitOrFail("Uncheckable: the running configuration is unchanged", "unsupported template was not held");
            probe(app, 2, "host:11|[3/4]|new");

            Files.writeString(properties, content("20", "5", "6", "recovered"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && app.output().stream()
                    .filter(s -> s.contains("Applied 4 property changes")).count() < 2) Thread.sleep(25);
            assertEquals(2, app.output().stream().filter(s -> s.contains("Applied 4 property changes")).count(), app.tail());
            probe(app, 3, "host:21|[5/6]|recovered");
        }
    }

    private static String content(String port, String a, String b, String label) {
        return "tpl.port=" + port + "\ntpl.a=" + a + "\ntpl.b=" + b + "\ntpl.label=" + label + "\n";
    }

    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("PROBE" + stage + "=", "probe missing");
        String actual = app.latest("PROBE" + stage + "=");
        System.out.println("[computed-template] " + actual);
        assertEquals("PROBE" + stage + "=" + expected, actual, app.tail());
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.beans.factory.annotation.Value;
            @Configuration(proxyBeanMethods = false)
            @PropertySource("classpath:application.properties")
            public class App {
                @Value("host:#{${tpl.port} + 1}") String endpoint;
                @Value("[#{${tpl.a}}/#{${tpl.b}}]") String pair;
                @Value("${tpl.label}") String label;
                public static void main(String[] args) throws Exception {
                    try (var context = new AnnotationConfigApplicationContext(App.class)) {
                        App original = context.getBean(App.class);
                        System.out.println("CFG_READY=" + original.endpoint + "|" + original.pair + "|" + original.label);
                        for (int i = 1; i <= 3; i++) {
                            Path probe = Path.of(System.getProperty("probe.dir"), "probe" + i);
                            while (!Files.exists(probe)) Thread.sleep(20);
                            App bean = context.getBean(App.class);
                            System.out.println("PROBE" + i + "=" + bean.endpoint + "|" + bean.pair + "|" + bean.label);
                        }
                    }
                }
            }
            """;
}
