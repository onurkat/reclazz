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

class AddedBeanSelectionReloadTest {
    @TempDir Path tmp;

    @Test
    void addedFactoryMetadataChangesSelectionAndRecoversAfterRemovalOrAmbiguity() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Config", config(0, "", "", false))
                .with("Transport", TRANSPORT).with("Product", PRODUCT).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, "missing/missing");
            reload(app, 1, "first", "second", true, null); probe(app, 1, "first/second");
            reload(app, 2, "second", "first", true, null); probe(app, 2, "second/first");
            reload(app, 3, "", "first", true, "NoUniqueBeanDefinitionException"); probe(app, 3, "missing/first");
            reload(app, 4, "second", "", true, "NoSuchBeanDefinitionException"); probe(app, 4, "second/missing");
            reload(app, 5, "both", "first", true, "NoUniqueBeanDefinitionException"); probe(app, 5, "missing/first");
            reload(app, 6, "first", "second", true, null); probe(app, 6, "first/second");
            reload(app, 7, "", "", false, null); probe(app, 7, "missing/missing");
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=9", "every successful consumer must close once");
            System.out.println("[bean-selection] CLOSED=9");
        }
    }

    private void reload(WatchedApp app, int stage, String primary, String fast, boolean methods, String failure) throws Exception {
        int before = app.output().size();
        app.rewrite("Config", config(stage, primary, fast, methods));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
        assertEquals(stage, reloads(app), app.tail());
        if (failure != null)
            assertTrue(app.output().stream().skip(before).anyMatch(s -> s.contains(failure)), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Config") || s.contains("Structural reload: app.Config")).count();
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("SELECT" + stage + "=", "probe did not run");
        String observed = app.latest("SELECT" + stage + "=");
        System.out.println("[bean-selection] " + observed);
        assertEquals("SELECT" + stage + "=" + expected + ":identities-ok", observed, app.tail());
    }
    private static String config(int version, String primary, String fast, boolean methods) {
        String factories = methods ? """
                @Bean private Product client(Transport candidate) { return new Product(candidate); }
                @Bean private Product qualified(@Qualifier("fast") Transport candidate) { return new Product(candidate); }
                %s @Bean public Transport first() { return new Transport("first"); }
                %s @Bean public Transport second() { return new Transport("second"); }
                """.formatted(metadata("first", primary, fast), metadata("second", primary, fast)) : "";
        return """
                package app;
                import org.springframework.context.annotation.*;
                import org.springframework.beans.factory.annotation.Qualifier;
                @Configuration(proxyBeanMethods=false)
                public class Config {
                    public int version() { return %d; }
                    %s
                }
                """.formatted(version, factories);
    }
    private static String metadata(String name, String primary, String fast) {
        return (primary.equals(name) || primary.equals("both") ? "@Primary " : "")
                + (fast.equals(name) ? "@Qualifier(\"fast\") " : "");
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
                public Product(Transport transport) { this.transport = transport; }
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
                    for (int stage = 0; stage < 8; stage++) {
                        while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                        String[] values = new String[2];
                        boolean identities = original == context.getBean("original") && !context.containsBean("fast");
                        int index = 0;
                        for (String name : new String[]{"client", "qualified"}) {
                            Product product = context.containsBean(name) ? context.getBean(name, Product.class) : null;
                            values[index++] = product == null ? "missing" : product.transport.name;
                            identities &= product == null || (product == context.getBean(name)
                                && product.transport == context.getBean(product.transport.name));
                        }
                        if (stage == 7) identities &= !context.containsBean("first") && !context.containsBean("second");
                        System.out.println("SELECT" + stage + "=" + String.join("/", values)
                            + (identities ? ":identities-ok" : ":wrong-identity"));
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close();
                    System.out.println("CLOSED=" + Product.closed);
                }
            }
            """;
}
