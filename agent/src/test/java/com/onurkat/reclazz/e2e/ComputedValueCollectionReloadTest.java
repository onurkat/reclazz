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

/** A property change re-evaluates an inline list/map @Value, and an uncheckable
 *  candidate leaves every old value in place. */
class ComputedValueCollectionReloadTest {
    @TempDir Path tmp;

    @Test
    void inlineCollectionValuesFollowPropertyChangesAndHoldOnUncheckable() throws Exception {
        Path properties = Files.createDirectories(tmp.resolve("classes")).resolve("application.properties");
        Files.writeString(properties, content("1", "2", "old"));
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("CFG_READY=[1, 2]:{x=1, y=2}:old", "Spring must evaluate the initial collections");
            app.awaitOrFail("] Watching 1 director", "watcher did not start");

            Files.writeString(properties, content("7", "8", "new"));
            app.awaitOrFail("Applied 3 property changes", "valid candidate was not applied");
            probe(app, 1, "[7, 8]:{x=7, y=8}:new");

            // A non-numeric value parses as a SpEL identifier, which the checker
            // cannot evaluate: the whole save is held and every value stays.
            Files.writeString(properties, content("oops", "8", "rejected"));
            app.awaitOrFail("Uncheckable: the running configuration is unchanged", "unsupported expression was not held");
            probe(app, 2, "[7, 8]:{x=7, y=8}:new");

            Files.writeString(properties, content("10", "11", "recovered"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && app.output().stream()
                    .filter(s -> s.contains("Applied 3 property changes")).count() < 2) Thread.sleep(25);
            assertEquals(2, app.output().stream().filter(s -> s.contains("Applied 3 property changes")).count(), app.tail());
            probe(app, 3, "[10, 11]:{x=10, y=11}:recovered");
        }
    }

    private static String content(String a, String b, String label) {
        return "coll.a=" + a + "\ncoll.b=" + b + "\ncoll.label=" + label + "\n";
    }

    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("PROBE" + stage + "=", "probe missing");
        String actual = app.latest("PROBE" + stage + "=");
        System.out.println("[computed-collection] " + actual);
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
                @Value("#{ {${coll.a}, ${coll.b}} }") java.util.List<Integer> ports;
                @Value("#{ {'x': ${coll.a}, 'y': ${coll.b}} }") java.util.Map<String,Integer> weights;
                @Value("${coll.label}") String label;
                public static void main(String[] args) throws Exception {
                    try (var context = new AnnotationConfigApplicationContext(App.class)) {
                        App original = context.getBean(App.class);
                        System.out.println("CFG_READY=" + original.ports + ":" + original.weights + ":" + original.label);
                        for (int i = 1; i <= 3; i++) {
                            Path probe = Path.of(System.getProperty("probe.dir"), "probe" + i);
                            while (!Files.exists(probe)) Thread.sleep(20);
                            App bean = context.getBean(App.class);
                            System.out.println("PROBE" + i + "=" + bean.ports + ":" + bean.weights + ":" + bean.label);
                        }
                    }
                }
            }
            """;
}
