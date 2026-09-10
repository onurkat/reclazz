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

class AddedEventListenerOnProxyReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eventListenerAddedToACacheProxiedSingletonRunsOnTheRealTarget(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Service", service(0, false)).with("Ping", PING).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "fixture did not cross a module boundary");
            probe(app, 0, "proxied=true cache=ok events=0");

            // Add an @EventListener: it must run on the real target, so the
            // target's event counter (read through the proxy) advances, and the
            // existing @Cacheable advice keeps working.
            reload(app, service(1, true), 1);
            probe(app, 1, "proxied=true cache=ok events=fired");

            // Remove the listener: the target stops receiving events.
            reload(app, service(2, false), 2);
            probe(app, 2, "proxied=true cache=ok events=silent");
            System.out.println("[event-on-proxy] child=" + child + " ok");
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
        System.out.println("[event-on-proxy] " + observed);
        assertEquals("R" + stage + "=" + expected, observed, app.tail());
    }

    private static String service(int version, boolean listening) {
        String listener = listening ? """
                @org.springframework.context.event.EventListener
                public void onPing(Ping ping) { events++; }
                """ : "";
        return """
                package app;
                import org.springframework.cache.annotation.Cacheable;
                @org.springframework.stereotype.Service
                public class Service {
                    private int events;
                    private int creations;
                    public int version() { return %d; }
                    public int events() { return events; }
                    @Cacheable("values") public int compute(String key) { return ++creations; }
                    %s
                }
                """.formatted(version, listener);
    }
    private static final String PING = "package app; public class Ping { }";

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.cache.annotation.EnableCaching;
            import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
            @Configuration(proxyBeanMethods=false)
            @EnableCaching
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
                        Service service = context.getBean(Service.class);
                        boolean proxied = service.getClass() != Service.class;
                        String key = "k" + stage;
                        int c1 = service.compute(key); int c2 = service.compute(key);
                        String cache = c1 == c2 ? "ok" : "broken:" + c1 + "/" + c2;
                        context.publishEvent(new Ping());
                        String events;
                        if (stage == 0) events = String.valueOf(service.events());
                        else if (stage == 1) events = service.events() == 1 ? "fired" : "missed:" + service.events();
                        else events = service.events() == 0 ? "silent" : "still-firing:" + service.events();
                        System.out.println("R" + stage + "=proxied=" + proxied + " cache=" + cache + " events=" + events);
                    }
                    context.close();
                }
            }
            """;
}
