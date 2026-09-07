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
import static org.junit.jupiter.api.Assertions.*;

class CacheComputationCrossingReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void computationCrossingReloadCannotLeaveAStaleEntry(boolean sync) throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dtest.release=" + tmp.resolve("release"))
                .with("App", APP).with("PriceService", SERVICE.formatted(sync))
                .with("DiscountRules", rule(10)).start()) {
            app.awaitOrFail("HELD=10", "first cache miss did not reach latch");
            app.awaitOrFail("] Watching ", "watcher did not start");
            app.rewrite("DiscountRules", rule(20));
            app.awaitOrFail("Reloaded app.DiscountRules", "helper did not reload");
            Files.createFile(tmp.resolve("release"));
            app.awaitOrFail("HELD_RESULT=10", "old computation did not finish unchanged");
            app.awaitOrFail("NEXT=", "next computation missing");
            String next = app.output().stream().filter(s -> s.startsWith("NEXT=")).findFirst().orElseThrow();
            assertEquals("NEXT=20", next, app.tail());
            System.out.println("[cache-crossing] sync=" + sync + " HELD_RESULT=10 " + next);
        }
    }
    private static String rule(int n) { return "package app;\npublic class DiscountRules { public static int calculate() { return " + n + "; } }"; }
    private static final String SERVICE = """
            package app;
            @org.springframework.stereotype.Service
            public class PriceService {
                @org.springframework.cache.annotation.Cacheable(value="prices", sync=%s)
                public int value() {
                    int v = DiscountRules.calculate();
                    if (v == 10) {
                        System.out.println("HELD=" + v);
                        try {
                            while (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("test.release")))) Thread.sleep(10);
                        } catch (InterruptedException e) { throw new RuntimeException(e); }
                    }
                    return v;
                }
            }
            """;
    private static final String APP = """
            package app;
            import org.springframework.context.annotation.*;
            @Configuration @org.springframework.cache.annotation.EnableCaching @ComponentScan("app")
            public class App {
                @Bean public org.springframework.cache.CacheManager cacheManager() {
                    return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("prices");
                }
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext(App.class);
                    var service = context.getBean(PriceService.class);
                    Thread held = new Thread(() -> System.out.println("HELD_RESULT=" + service.value()));
                    held.start(); held.join();
                    System.out.println("NEXT=" + service.value());
                    Thread.sleep(60000);
                }
            }
            """;
}
