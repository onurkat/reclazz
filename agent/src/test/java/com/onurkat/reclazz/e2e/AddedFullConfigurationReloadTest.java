/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class AddedFullConfigurationReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void factoriesKeepSingletonIdentityAcrossCallsCyclesEditsAndRemoval(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Dtest.dir=" + tmp).with("App", APP)
                .with("Config", config(0)).with("Observer", observer(false))
                .with("Product", "package app; public record Product(int version) {}")
                .with("Pair", "package app; public record Pair(Product first, Product second, Object seed) {}")
                .start()) {
            app.awaitOrFail("READY", "context not ready"); app.awaitOrFail("] Watching ", "watcher not ready");
            for (int stage = 1; stage <= 5; stage++) {
                // Complete each save before publishing the probe. A per-class log
                // within a batch precedes that batch's dependent-bean recreation.
                app.rewrite("Config", config(stage));
                awaitReload(app, "Config", stage);
                if (stage == 1 || stage == 4 || stage == 5) {
                    app.rewrite("Observer", observer(stage != 4));
                    awaitReload(app, "Observer", stage < 4 ? 1 : stage - 2);
                }
                Files.createFile(tmp.resolve("probe" + stage));
                app.awaitOrFail("PROBE" + stage + "=", "probe not executed");
                String expected = stage == 2 ? "cycle" : stage == 4 ? "removed" : "singleton:" + stage;
                assertEquals("PROBE" + stage + "=" + expected, app.latest("PROBE" + stage + "="), app.tail());
                System.out.println(app.latest("PROBE" + stage + "="));
            }
        }
    }
    private void awaitReload(WatchedApp app, String type, int expected) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end && reloads(app, type) < expected) Thread.sleep(25);
        assertEquals(expected, reloads(app, type), app.tail());
    }
    private long reloads(WatchedApp app, String type) {
        return app.output().stream().filter(s -> s.contains("Reloaded app." + type) || s.contains("Structural reload: app." + type)).count();
    }
    private static String config(int stage) {
        String factories = stage == 0 || stage == 4 ? "" : """
            @org.springframework.context.annotation.Bean public Pair pair() { return new Pair(leaf(), leaf(), seed()); }
            @org.springframework.context.annotation.Bean(name={"leaf", "leafAlias"}) public Product leaf() { %s }
            @org.springframework.context.annotation.Bean public static Product stat() { return new Product(-%d); }
            """.formatted(stage == 2 ? "return pair().first();" : "return new Product(" + stage + ");", stage);
        return """
            package app;
            @org.springframework.context.annotation.Configuration
            public class Config {
                @org.springframework.context.annotation.Bean public Object seed() { return new Object(); }
                public Product fromOldReference() { %s }
                public java.util.function.Supplier<Product> reference() { %s }
                public boolean staticCallsArePlain() { %s }
                %s
            }
            """.formatted(stage == 0 || stage == 4 ? "return null;" : "return leaf();", stage == 0 || stage == 4 ? "return () -> null;" : "return this::leaf;", stage == 0 || stage == 4 ? "return true;" : "return stat()!=stat();", factories);
    }
    private static String observer(boolean added) {
        return "package app; public class Observer { public static boolean same(Config config, Object leaf) { return "
                + (added ? "config.leaf() == leaf" : "leaf == null") + "; } }";
    }
    private static final String APP = """
        package app;
        public class App {
            public static void main(String[] args) throws Exception {
                var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(Config.class);
                Config held = context.getBean(Config.class); context.getBean("seed");
                if (!Observer.same(held, null)) throw new AssertionError("initial observer");
                System.out.println("READY");
                java.util.function.Supplier<Product> savedReference = null;
                for (int stage=1; stage<=5; stage++) {
                    while (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("test.dir"), "probe"+stage))) Thread.sleep(25);
                    try {
                    String answer;
                    if(stage==2) {
                        if(context.containsBean("pair") || context.containsBean("leaf")) throw new AssertionError("failed cycle left products");
                        answer="cycle";
                    } else if(stage==4) {
                        if(context.containsBean("pair") || context.containsBean("leaf") || context.containsBean("leafAlias") || context.containsBean("stat")) throw new AssertionError("removed factory remains");
                        if(held.fromOldReference()!=null) throw new AssertionError("old helper stale");
                        answer="removed";
                    } else {
                        Product leaf=context.getBean("leaf",Product.class); Pair pair=context.getBean("pair",Pair.class);
                        if(savedReference==null) savedReference=held.reference();
                        if(savedReference.get()!=leaf) throw new AssertionError("retained method reference is stale");
                        if(pair.first()!=leaf || pair.second()!=leaf || leaf!=context.getBean("leafAlias")
                                || held.fromOldReference()!=leaf || held.reference().get()!=leaf || !Observer.same(held,leaf) || !held.staticCallsArePlain()
                                || pair.seed()!=context.getBean("seed")
                                || context.getBean("stat",Product.class).version()!=-stage)
                            throw new AssertionError("singleton identity changed: " + (pair.first()==leaf) + ":" + (pair.second()==leaf)
                                + ":" + (held.fromOldReference()==leaf) + ":" + Observer.same(held,leaf)
                                + ":" + (pair.seed()==context.getBean("seed")));
                        answer="singleton:"+leaf.version();
                    }
                    System.out.println("PROBE"+stage+"="+answer);
                    } catch (Throwable failure) { System.out.println("PROBE"+stage+"=failed:"+failure); throw failure; }
                }
                context.close();
            }
        }
        """;
}
