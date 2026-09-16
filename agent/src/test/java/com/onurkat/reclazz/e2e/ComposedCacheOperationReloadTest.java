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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ComposedCacheOperationReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void composedCacheFollowsSavesThroughHeldProxy(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()).jvmArgs("-Dprobe.dir=" + tmp)
                .with("App", APP).with("Cached", CACHED).with("Catalog", CATALOG)
                .with("Store", store(0)).with("Probe", probe(false));
        if (child) builder.childClassLoader();
        try (var app = builder.start()) {
            app.awaitOrFail("READY=true", "native caching proxy missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            String[] expected = {
                    "1:k1:1|1:k1:1:1:true:false", "2:k2:2|2:k2:2:2:true:false",
                    "3:k3:3|3:k3:3:3:true:false", "4:k4:4|4:k4:5:5:true:false",
                    "5:k5:6|5:k5:7:7:true:false", "removed:7:true:false",
                    "7:k7:8|7:k7:8:8:true:false"
            };
            for (int stage = 1; stage <= 7; stage++) {
                if (stage == 1) app.rewriteAll(Map.of("Store", store(stage), "Probe", probe(true)));
                else app.rewrite("Store", store(stage));
                awaitReloads(app, "Store", stage);
                if (stage == 1) awaitReloads(app, "Probe", 1);
                Files.createFile(tmp.resolve("probe" + stage));
                app.awaitOrFail("R" + stage + "=", "composed cache probe failed");
                assertEquals("R" + stage + "=" + expected[stage - 1], app.latest("R" + stage + "="), app.tail());
                System.out.println("[composed-cache] child=" + child + " " + app.latest("R" + stage + "="));
            }
        }
    }

    private static void awaitReloads(WatchedApp app, String name, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (reloads(app, name) < count && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(count, reloads(app, name), app.tail());
    }
    private static long reloads(WatchedApp app, String name) {
        return app.output().stream().filter(s -> s.contains("Reloaded app." + name + " ")
                || s.contains("Structural reload: app." + name + " ")).count();
    }
    private static String probe(boolean added) {
        return "package app; public class Probe { public static String call(Store store,String key) { return "
                + (added ? "store.value(key)" : "\"ready\"") + "; } }";
    }
    private static String store(int version) {
        String annotation = switch (version) {
            case 2 -> "@Catalog";
            case 3 -> "@Catalog(value=\"values\", key=\"'other/' + #p0\")";
            case 4 -> "@Cached(condition=\"#p0 != 'k4'\")";
            case 5 -> "";
            default -> "@Cached";
        };
        String method = version == 0 || version == 6 ? "" : """
                %s
                public String value(String key) { calls++; return "%d:"+key+":"+calls; }
                """.formatted(annotation, version);
        return """
                package app;
                public class Store {
                    private int calls;
                    @org.springframework.cache.annotation.Cacheable("control") public int original() { return 11; }
                    public int calls() { return calls; }
                    %s
                }
                """.formatted(method);
    }
    private static final String CACHED = """
            package app;
            import java.lang.annotation.*;
            import org.springframework.cache.annotation.Cacheable;
            import org.springframework.core.annotation.AliasFor;
            @Target({ElementType.TYPE,ElementType.METHOD,ElementType.ANNOTATION_TYPE}) @Retention(RetentionPolicy.RUNTIME)
            @Cacheable(cacheNames="values", key="#p0")
            public @interface Cached {
                @AliasFor(annotation=Cacheable.class,attribute="cacheNames") String[] regions() default {"values"};
                @AliasFor(annotation=Cacheable.class,attribute="key") String key() default "#p0";
                @AliasFor(annotation=Cacheable.class,attribute="condition") String condition() default "";
            }
            """;
    private static final String CATALOG = """
            package app;
            import java.lang.annotation.*;
            import org.springframework.core.annotation.AliasFor;
            @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Cached(regions="changed")
            public @interface Catalog {
                @AliasFor(annotation=Cached.class,attribute="regions") String[] value() default {"changed"};
                @AliasFor(annotation=Cached.class,attribute="key") String key() default "'catalog/' + #p0";
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.cache.annotation.EnableCaching;
            @Configuration(proxyBeanMethods=false) @EnableCaching(proxyTargetClass=true)
            public class App {
                @Bean public org.springframework.cache.CacheManager cacheManager() {
                    return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("values","changed","control");
                }
                @Bean public Store store() { return new Store(); }
                public static void main(String[] args) throws Exception {
                    try (var context=new AnnotationConfigApplicationContext(App.class)) {
                        Store held=context.getBean(Store.class);
                        var caches=context.getBean(org.springframework.cache.CacheManager.class);
                        System.out.println("READY="+(held instanceof org.springframework.aop.framework.Advised));
                        for (int stage=1;stage<=7;stage++) {
                            while (!Files.exists(Path.of(System.getProperty("probe.dir"),"probe"+stage))) Thread.sleep(20);
                            String result;
                            if (stage==6) {
                                try { Probe.call(held,"removed"); throw new AssertionError("removed body ran"); }
                                catch (IllegalStateException expected) {
                                    if (!expected.getMessage().contains("operation method was removed")) throw expected;
                                    result="removed";
                                }
                            } else {
                                String key="k"+stage;
                                String first=Probe.call(held,key), second=Probe.call(held,key);
                                result=first+"|"+second;
                                if (stage<=3) {
                                    String region=stage==2?"changed":"values";
                                    String cacheKey=stage==2?"catalog/"+key:stage==3?"other/"+key:key;
                                    if (!second.equals(caches.getCache(region).get(cacheKey).get())) throw new AssertionError("wrong alias/key resolution");
                                }
                            }
                            if (held.original()!=11) throw new AssertionError("native method changed");
                            boolean reflected=java.util.Arrays.stream(Store.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("value"));
                            System.out.println("R"+stage+"="+result+":"+held.calls()+":"+(held==context.getBean(Store.class))+":"+reflected);
                        }
                    }
                }
            }
            """;
}
