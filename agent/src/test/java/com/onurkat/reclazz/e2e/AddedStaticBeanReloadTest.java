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

class AddedStaticBeanReloadTest {
    @TempDir Path tmp;

    @Test
    void staticCompanionFactoriesFollowEditsFailureRecoveryAndRemoval() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Config", config(0, false, false))
                .with("Product", PRODUCT).with("Consumer", CONSUMER).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, "missing");
            reload(app, 1, true, true); probe(app, 1, "1");
            reload(app, 2, true, true); probe(app, 2, "2");
            reload(app, 3, true, true); probe(app, 3, "missing");
            reload(app, 4, true, true); probe(app, 4, "4");
            reload(app, 5, true, false); probe(app, 5, "missing");
            reload(app, 6, true, true); probe(app, 6, "6");
            reload(app, 7, false, false); probe(app, 7, "missing");
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=4", "every successful product must close once");
            System.out.println("[static-beans] CLOSED=4");
        }
    }

    private void reload(WatchedApp app, int stage, boolean method, boolean annotation) throws Exception {
        app.rewrite("Config", config(stage, method, annotation));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
        assertEquals(stage, reloads(app), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Config") || s.contains("Structural reload: app.Config")).count();
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("STATIC" + stage + "=", "probe did not run");
        String observed = app.latest("STATIC" + stage + "=");
        System.out.println("[static-beans] " + observed);
        assertEquals("STATIC" + stage + "=" + expected + ":identities-ok", observed, app.tail());
    }
    private static String config(int version, boolean method, boolean annotation) {
        String selected = version == 2 ? "legacy" : "fast";
        String factories = method ? """
                %s public static Consumer client(@Qualifier("%s") Product product) { return new Consumer(product); }
                %s public Consumer instanceClient(@Qualifier("%s") Product product) { return new Consumer(product); }
                %s private static Product product(String token) {
                    %s return new Product(%d, token);
                }
                """.formatted(annotation ? "@Bean" : "", selected, annotation ? "@Bean" : "", selected,
                annotation ? "@Bean({\"main\",\"legacy\"}) @Primary" + (version == 2 ? "" : " @Qualifier(\"fast\")") : "",
                version == 3 ? "if (true) throw new IllegalStateException(\"static factory failure\");" : "", version) : "";
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
    private static final String CONSUMER = """
            package app;
            public record Consumer(Product product) { }
            """;
    private static final String PRODUCT = """
            package app;
            public class Product implements AutoCloseable {
                public static int closed;
                public final int version;
                public final String token;
                public Product(int version, String token) { this.version = version; this.token = token; }
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
                    String token = new String("current token");
                    context.getBeanFactory().registerSingleton("token", token);
                    Path dir = Path.of(System.getProperty("test.dir"));
                    System.out.println("READY");
                    for (int stage = 0; stage < 8; stage++) {
                        while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                        Product product = context.containsBean("main") ? context.getBean("main", Product.class) : null;
                        String value = product == null ? "missing" : "" + product.version;
                        boolean identities = token == context.getBean("token") && !context.containsBean("product");
                        if (product == null) identities &= !context.containsBean("client") && !context.containsBean("instanceClient")
                                && !context.containsBean("legacy") && !context.getDefaultListableBeanFactory().isAlias("legacy");
                        else identities &= context.getBean("legacy") == product && context.getBean(Product.class) == product
                                && context.getBean("client", Consumer.class).product() == product
                                && context.getBean("instanceClient", Consumer.class).product() == product && product.token == token;
                        System.out.println("STATIC" + stage + "=" + value + (identities ? ":identities-ok" : ":wrong-identity"));
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close();
                    System.out.println("CLOSED=" + Product.closed);
                }
            }
            """;
}
