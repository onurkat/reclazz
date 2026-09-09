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

class AddedBeanArgumentsReloadTest {
    @TempDir Path tmp;

    @Test
    void parametersAndQualifierEditsReachTheCompanionAndRecoverFromMissingBeans() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Config", config(0, "blue", false, false))
                .with("Transport", TRANSPORT).with("Product", PRODUCT).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, "missing");
            reload(app, config(2, "blue", true, true), 1); probe(app, 1, "blue:2");
            reload(app, config(3, "blue", true, true), 2); probe(app, 2, "blue:3");
            reload(app, config(4, "red", true, true), 3); probe(app, 3, "red:4");
            reload(app, config(5, "absent", true, true), 4); probe(app, 4, "missing");
            reload(app, config(6, "red", true, true), 5); probe(app, 5, "red:6");
            reload(app, config(7, "blue", true, false), 6); probe(app, 6, "missing");
            reload(app, config(8, "blue", true, true), 7); probe(app, 7, "blue:8");
            reload(app, config(9, "blue", false, false), 8); probe(app, 8, "missing");
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=5", "every successful product must close once");
            System.out.println("[bean-arguments] CLOSED=5");
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Config.client() was added")), app.tail());
        }
    }

    private void reload(WatchedApp app, String source, int expected) throws Exception {
        app.rewrite("Config", source);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < expected) Thread.sleep(25);
        assertEquals(expected, reloads(app), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Config") || s.contains("Structural reload: app.Config")).count();
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("ARGS" + stage + "=", "probe did not run");
        String observed = app.latest("ARGS" + stage + "=");
        System.out.println("[bean-arguments] " + observed);
        assertEquals("ARGS" + stage + "=" + expected + ":identities-ok", observed, app.tail());
    }
    private static String config(int version, String qualifier, boolean method, boolean annotation) {
        String client = method ? (annotation ? "@org.springframework.context.annotation.Bean\n" : "")
                + "private Product client(@org.springframework.beans.factory.annotation.Qualifier(\"" + qualifier
                + "\") Transport transport) { return new Product(transport, " + version + "); }" : "";
        return """
                package app;
                @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
                public class Config {
                    public int version() { return %d; }
                    %s
                    %s
                }
                """.formatted(version, client, version == 0 ? "" : """
                    @org.springframework.context.annotation.Bean
                    public Transport blue() { return new Transport("blue"); }
                    @org.springframework.context.annotation.Bean
                    public Transport red() { return new Transport("red"); }
                    """);
    }
    private static final String TRANSPORT = """
            package app;
            public class Transport {
                public final String name;
                public Transport(String name) { this.name = name; }
            }
            """;
    private static final String PRODUCT = """
            package app;
            public class Product implements AutoCloseable {
                public static int closed;
                public final Transport transport;
                public final int version;
                public Product(Transport transport, int version) { this.transport = transport; this.version = version; }
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
                        Product product = context.containsBean("client") ? context.getBean("client", Product.class) : null;
                        String value = product == null ? "missing" : product.transport.name + ":" + product.version;
                        boolean identities = original == context.getBean("original") && (product == null
                            || (product == context.getBean("client") && product.transport == context.getBean(product.transport.name)));
                        System.out.println("ARGS" + stage + "=" + value + (identities ? ":identities-ok" : ":wrong-identity"));
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close();
                    System.out.println("CLOSED=" + Product.closed);
                }
            }
            """;
}
