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

import static org.junit.jupiter.api.Assertions.*;

class AddedBeanMethodReloadTest {
    @TempDir Path tmp;

    @Test
    void addedFactoryRegistersUpdatesRemovesAndRecovers() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Config", config(0, false, false))
                .with("Product", PRODUCT).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, "missing");
            reload(app, config(2, true, true), 1);
            probe(app, 1, "2");
            reload(app, config(3, true, true), 2);
            probe(app, 2, "3");
            reload(app, config(4, true, true), 3);
            probe(app, 3, "4");
            reload(app, config(5, true, false), 4);
            probe(app, 4, "missing");
            reload(app, config(6, true, true), 5);
            probe(app, 5, "6");
            reload(app, config(7, false, false), 6);
            probe(app, 6, "missing");
            reload(app, config(-1, true, true), 7);
            probe(app, 7, "missing");
            reload(app, config(9, true, true), 8);
            probe(app, 8, "9");
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=5", "every successful product must close exactly once");
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Config.added() was added")), app.tail());
        }
    }

    private void reload(WatchedApp app, String source, int expected) throws Exception {
        app.rewrite("Config", source);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && app.output().stream().filter(this::isReload).count() < expected)
            Thread.sleep(25);
        assertEquals(expected, app.output().stream().filter(this::isReload).count(), app.tail());
    }

    private boolean isReload(String line) {
        return line.contains("Reloaded app.Config") || line.contains("Structural reload: app.Config");
    }

    private void probe(WatchedApp app, int stage, String value) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("PROBE" + stage + "=", "probe did not run");
        String actual = app.latest("PROBE" + stage + "=");
        System.out.println("[added-bean] " + actual);
        assertEquals("PROBE" + stage + "=" + value + ":singleton:original-ok", actual, app.tail());
    }

    private static String config(int version, boolean method, boolean annotation) {
        return """
                package app;
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
                public class Config {
                    public int version() { return %d; }
                    %s
                }
                """.formatted(version, method ? (annotation ? "@org.springframework.context.annotation.Bean\n" : "")
                + "private Product added() { " + (version < 0 ? "throw new IllegalStateException(\"factory-failed\");"
                : "return new Product(version());") + " }" : "");
    }

    private static final String PRODUCT = """
            package app;
            public class Product implements AutoCloseable {
                public static int closed;
                public final int value;
                public Product(int value) { this.value = value; }
                public void close() { closed++; }
            }
            """;

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext(Config.class);
                    Object original = new Object();
                    context.getBeanFactory().registerSingleton("original", original);
                    Path dir = Path.of(System.getProperty("test.dir"));
                    System.out.println("READY");
                    for (int stage = 0; stage < 9; stage++) {
                        while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                        Object product = context.containsBean("added") ? context.getBean("added") : null;
                        String value = product == null ? "missing" : Integer.toString(((Product) product).value);
                        boolean singleton = product == null || product == context.getBean("added");
                        System.out.println("PROBE" + stage + "=" + value
                            + (singleton ? ":singleton" : ":duplicate")
                            + (original == context.getBean("original") ? ":original-ok" : ":original-changed"));
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close();
                    System.out.println("CLOSED=" + Product.closed);
                }
            }
            """;
}
