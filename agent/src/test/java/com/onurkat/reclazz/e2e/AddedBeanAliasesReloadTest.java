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

class AddedBeanAliasesReloadTest {
    @TempDir Path tmp;

    @Test
    void aliasesFollowAddedFactoriesAcrossEditsFailureRecoveryAndRemoval() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Config", config(0, "main", "legacy", false, false))
                .with("Product", PRODUCT).with("Consumer", CONSUMER).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, "missing");
            reload(app, 1, "main", "legacy", true, true); probe(app, 1, "1:main,legacy");
            reload(app, 2, "main", "current", true, true); probe(app, 2, "2:main,current");
            reload(app, 3, "current", "main", true, true); probe(app, 3, "3:current,main");
            reload(app, 4, "main", "legacy", true, true); probe(app, 4, "missing");
            reload(app, 5, "main", "legacy", true, true); probe(app, 5, "5:main,legacy");
            reload(app, 6, "main", "legacy", true, false); probe(app, 6, "missing");
            reload(app, 7, "main", "legacy", true, true); probe(app, 7, "7:main,legacy");
            reload(app, 8, "main", "legacy", false, false); probe(app, 8, "missing");
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=5", "every successful product must close once");
            System.out.println("[bean-aliases] CLOSED=5");
        }
    }

    private void reload(WatchedApp app, int stage, String name, String alias, boolean method, boolean annotation) throws Exception {
        app.rewrite("Config", config(stage, name, alias, method, annotation));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
        assertEquals(stage, reloads(app), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Config") || s.contains("Structural reload: app.Config")).count();
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("ALIASES" + stage + "=", "probe did not run");
        String observed = app.latest("ALIASES" + stage + "=");
        System.out.println("[bean-aliases] " + observed);
        assertEquals("ALIASES" + stage + "=" + expected + ":identities-ok", observed, app.tail());
    }
    private static String config(int version, String name, String alias, boolean method, boolean annotation) {
        String factories = method ? """
                %s public Consumer client(@Qualifier("%s") Product product) { return new Consumer(product); }
                %s private Product product() { %s return new Product(%d); }
                """.formatted(annotation ? "@Bean" : "", alias,
                annotation ? "@Bean({\"" + name + "\",\"" + alias + "\"})" : "",
                version == 4 ? "if (true) throw new IllegalStateException(\"test factory failure\");" : "", version) : "";
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
                public Product(int version) { this.version = version; }
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
                        String name = stage == 3 ? "current" : "main";
                        String alias = stage == 2 ? "current" : stage == 3 ? "main" : "legacy";
                        Product product = context.containsBean(name) ? context.getBean(name, Product.class) : null;
                        String value = product == null ? "missing" : product.version + ":" + name + "," + alias;
                        boolean identities = original == context.getBean("original") && !context.containsBean("product");
                        for (String candidate : new String[]{"main", "legacy", "current"}) {
                            if (product != null && (candidate.equals(name) || candidate.equals(alias)))
                                identities &= context.getBean(candidate) == product;
                            else identities &= !context.containsBean(candidate) && !context.getDefaultListableBeanFactory().isAlias(candidate);
                        }
                        if (product == null) identities &= !context.containsBean("client");
                        else identities &= context.getBean(Consumer.class).product() == product;
                        System.out.println("ALIASES" + stage + "=" + value + (identities ? ":identities-ok" : ":wrong-identity"));
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close();
                    System.out.println("CLOSED=" + Product.closed);
                }
            }
            """;
}
