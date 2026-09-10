/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AddedScheduledOnProxyReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scheduledMethodAddedToACacheProxiedSingletonRunsOnTheRealTarget(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Service", service(0, false)).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "fixture did not cross a module boundary");
            // The bean is a CGLIB proxy because of @Cacheable.
            probe(app, 0, "proxied=true cache=ok ticks=0");

            // Add a @Scheduled method: it must run on the real target, so the
            // target's tick counter (read through the proxy) advances, and the
            // existing @Cacheable advice keeps working.
            reload(app, service(1, true), 1);
            Files.createFile(tmp.resolve("await1"));
            app.awaitOrFail("TICKING", "added scheduled task did not run on the target");
            probe(app, 1, "proxied=true cache=ok ticks=growing");

            // Remove the scheduled method: the target stops ticking.
            reload(app, service(2, false), 2);
            probe(app, 2, "proxied=true cache=ok ticks=stopped");
            System.out.println("[scheduled-on-proxy] child=" + child + " ok");
        }
    }

    private void reload(WatchedApp app, String source, int stage) throws Exception {
        app.rewrite("Service", source);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
        assertEquals(stage, reloads(app), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Service") || s.contains("Structural reload: app.Service")).count();
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("R" + stage + "=", "probe did not run");
        String observed = app.latest("R" + stage + "=");
        System.out.println("[scheduled-on-proxy] " + observed);
        assertEquals("R" + stage + "=" + expected, observed, app.tail());
    }

    private static String service(int version, boolean scheduled) {
        String tick = scheduled ? """
                @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 25)
                public void tick() { ticks++; if (ticks == 1) System.out.println("TICKING"); }
                """ : "";
        return """
                package app;
                import org.springframework.cache.annotation.Cacheable;
                @org.springframework.stereotype.Service
                public class Service {
                    private int ticks;
                    private int creations;
                    public int version() { return %d; }
                    public int ticks() { return ticks; }
                    public int creations() { return creations; }
                    @Cacheable("values") public int compute(String key) { return ++creations; }
                    %s
                }
                """.formatted(version, tick);
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.cache.annotation.EnableCaching;
            import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
            import org.springframework.scheduling.annotation.EnableScheduling;
            @Configuration(proxyBeanMethods=false)
            @EnableCaching
            @EnableScheduling
            @ComponentScan("app")
            public class App {
                @org.springframework.context.annotation.Bean
                org.springframework.cache.CacheManager cacheManager() { return new ConcurrentMapCacheManager("values"); }
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext(App.class);
                    Path dir = Path.of(System.getProperty("test.dir"));
                    System.out.println("READY");
                    for (int stage = 0; stage <= 2; stage++) {
                        while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                        // Re-fetch: a structural reload recreates the singleton.
                        Service service = context.getBean(Service.class);
                        boolean proxied = service.getClass() != Service.class;
                        String key = "k" + stage;
                        int c1 = service.compute(key); int c2 = service.compute(key); // second is cached
                        String cache = c1 == c2 ? "ok" : "broken:" + c1 + "/" + c2;
                        String ticks;
                        if (stage == 0) {
                            ticks = String.valueOf(service.ticks());
                        } else if (stage == 1) {
                            while (!Files.exists(dir.resolve("await1"))) Thread.sleep(10);
                            int first = service.ticks(); Thread.sleep(200); int second = service.ticks();
                            ticks = first > 0 && second > first ? "growing" : "stuck:" + first + "/" + second;
                        } else {
                            int a = service.ticks(); Thread.sleep(200); int b = service.ticks();
                            ticks = b == a ? "stopped" : "still-ticking:" + a + "/" + b;
                        }
                        System.out.println("R" + stage + "=proxied=" + proxied + " cache=" + cache + " ticks=" + ticks);
                    }
                    context.close();
                }
            }
            """;
}
