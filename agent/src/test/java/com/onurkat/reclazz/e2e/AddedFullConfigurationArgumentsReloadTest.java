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

class AddedFullConfigurationArgumentsReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void argumentsAndHeldReferencesFollowFiveSaves(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Dtest.dir=" + tmp).with("App", APP)
                .with("Config", config(0)).with("Observer", observer(false))
                .with("Wire", "package app; public record Wire(String name) {}")
                .with("Product", "package app; public record Product(int version, Wire wire, int count) {}")
                .start()) {
            app.awaitOrFail("READY", "context not ready"); app.awaitOrFail("] Watching ", "watcher not ready");
            for (int stage = 1; stage <= 5; stage++) {
                app.rewrite("Config", config(stage));
                awaitReload(app, "Config", stage);
                if (stage == 1 || stage == 4 || stage == 5) {
                    app.rewrite("Observer", observer(stage != 4));
                    awaitReload(app, "Observer", stage < 4 ? 1 : stage - 2);
                }
                Files.createFile(tmp.resolve("probe" + stage));
                app.awaitOrFail("PROBE" + stage + "=", "probe not executed");
                String expected = stage == 4 ? "removed" : "full:" + stage + ":" + (stage + 4) + ":" + (stage + 10);
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
        boolean present = stage != 0 && stage != 4;
        String factories = !present ? "" : """
            @Bean @Lazy public Product leaf(@Qualifier("wire") Wire wire, @Value("%d") int count) {
                return new Product(%d, wire, count);
            }
            @Bean @Scope("prototype") public Product proto(@Qualifier("wire") Wire wire, @Value("%d") int count) {
                if(count < 0) throw new IllegalArgumentException("negative count");
                return new Product(%d, wire, count);
            }
            @Bean public Product auto(@Qualifier("wire") Wire wire, @Value("%d") int count) {
                return new Product(%d, wire, count);
            }
            """.formatted(stage + 4, stage, stage + 4, stage, stage + 4, stage)
                .replace("@Bean @Lazy", "@Bean(name={\"leaf\",\"leafAlias\"}) @Lazy");
        return """
            package app;
            import org.springframework.context.annotation.*;
            import org.springframework.beans.factory.annotation.*;
            @Configuration
            public class Config {
                @Bean public Wire wire() { return new Wire("resolved"); }
                public Product fromOldReference(Wire wire, int count) { %s }
                public Product prototypeCall(Wire wire, int count) { %s }
                public java.util.function.BiFunction<Wire,Integer,Product> reference() { %s }
                %s
            }
            """.formatted(present ? "return leaf(wire,count);" : "return null;",
                    present ? "return proto(wire,count);" : "return null;",
                    present ? "return this::leaf;" : "return null;", factories);
    }
    private static String observer(boolean added) {
        return "package app; public class Observer { public static boolean same(Config config, Product leaf) { return "
                + (added ? "config.leaf(null, 99) == leaf" : "leaf == null") + "; } }";
    }
    private static final String APP = """
        package app;
        public class App {
            public static void main(String[] args) throws Exception {
                try(var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(Config.class)) {
                    Config held = context.getBean(Config.class);
                    if (!Observer.same(held, null)) throw new AssertionError("initial observer");
                    System.out.println("READY");
                    java.util.function.BiFunction<Wire,Integer,Product> savedReference = null;
                    for (int stage=1; stage<=5; stage++) {
                        while (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("test.dir"), "probe"+stage))) Thread.sleep(25);
                        try {
                            String answer;
                            if(stage==4) {
                                for(String name : new String[]{"leaf","leafAlias","auto","proto"})
                                    if(context.containsBean(name)) throw new AssertionError("removed factory remains: "+name);
                                if(held.fromOldReference(null, 0)!=null || !Observer.same(held,null)) throw new AssertionError("old helper stale");
                                // This reference captured v1. Removal keeps that original body,
                                // while the Spring registration/reference route is gone.
                                Product retained=savedReference.apply(null, 1);
                                if(retained.version()!=1 || retained.wire()!=null || retained.count()!=1)
                                    throw new AssertionError("removed reference lost its original captured Java body: "+retained);
                                if(context.containsBean("leaf")) throw new AssertionError("removed reference recreated registration");
                                answer="removed";
                            } else {
                                Wire resolved = context.getBean("wire",Wire.class);
                                Product auto = context.getBean("auto",Product.class);
                                if(auto.wire()!=resolved || auto.version()!=stage || auto.count()!=stage+4)
                                    throw new AssertionError("resolved factory arguments stale: "+auto);
                                if(savedReference==null) savedReference=held.reference();
                                Wire explicit=new Wire("explicit"+stage);
                                Product leaf=savedReference.apply(explicit,stage+10);
                                if(leaf.wire()!=explicit || leaf.count()!=stage+10 || leaf.version()!=stage
                                        || leaf!=context.getBean("leafAlias") || leaf!=held.fromOldReference(null,99)
                                        || !Observer.same(held,leaf)) throw new AssertionError("explicit singleton arguments/identity: "+leaf);
                                Product prototype=held.prototypeCall(null,stage+20);
                                if(prototype.wire()!=null || prototype.count()!=stage+20 || prototype.version()!=stage
                                        || prototype==held.prototypeCall(null,stage+20)) throw new AssertionError("prototype null/identity: "+prototype);
                                boolean failed=false;
                                try { held.prototypeCall(explicit,-1); } catch(Throwable expected) { failed=true; }
                                if(!failed) throw new AssertionError("negative input did not fail");
                                Product recovered=context.getBean("proto",Product.class);
                                if(recovered.wire()!=resolved || recovered.count()!=stage+4) throw new AssertionError("failed call leaked arguments: "+recovered);
                                Product manual=new Config().fromOldReference(explicit,42);
                                if(manual==leaf || manual.wire()!=explicit || manual.count()!=42) throw new AssertionError("manual config lost Java arguments");
                                for(var method:Config.class.getDeclaredMethods())
                                    if(method.getName().equals("leaf")) throw new AssertionError("added method visible to reflection");
                                answer="full:"+stage+":"+auto.count()+":"+leaf.count();
                            }
                            System.out.println("PROBE"+stage+"="+answer);
                        } catch (Throwable failure) { System.out.println("PROBE"+stage+"=failed:"+failure); throw failure; }
                    }
                }
            }
        }
        """;
}
