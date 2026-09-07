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

class CacheDependencyReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void helperAndServiceEvictOnlyTheirTransitiveCaches(boolean auto) throws Exception {
        var builder = WatchedApp.in(tmp);
        if (auto) builder.mavenLayout();
        try (var app = builder.classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100,autoCompile=" + auto)
                .jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("PriceService", price(0)).with("DiscountRules", rule(10))
                .with("CatalogService", CATALOG).with("QuoteService", QUOTE).with("Counters", COUNTERS).start()) {
            app.awaitOrFail("WARM=10:10:description:1", "cache warmup failed");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (auto) Files.writeString(tmp.resolve("src/main/java/app/DiscountRules.java"), rule(20));
            else app.rewrite("DiscountRules", rule(20));
            app.awaitOrFail("Reloaded app.DiscountRules", "helper reload failed");
            Files.createFile(tmp.resolve("probe1"));
            app.awaitOrFail("FIRST=", "first probe missing");
            String first = app.output().stream().filter(s -> s.startsWith("FIRST=")).findFirst().orElseThrow();
            System.out.println("[cache-dependencies] auto=" + auto + " " + first);
            assertEquals("FIRST=20:20:description:1", first, app.tail());
            if (auto) Files.writeString(tmp.resolve("src/main/java/app/PriceService.java"), price(1));
            else app.rewrite("PriceService", price(1));
            app.awaitOrFail("Reloaded app.PriceService", "service reload failed");
            Files.createFile(tmp.resolve("probe2"));
            app.awaitOrFail("SECOND=", "second probe missing");
            assertTrue(app.output().contains("SECOND=21:21:description:1"), app.tail());
            System.out.println("[cache-dependencies] auto=" + auto + " SECOND=21:21:description:1");
        }
    }
    private static String rule(int n) { return "package app;\npublic class DiscountRules { public static int calculate() { return " + n + "; } }"; }
    private static String price(int n) { return """
            package app;
            @org.springframework.stereotype.Service
            public class PriceService {
                @org.springframework.cache.annotation.Cacheable("prices")
                public int value() { return DiscountRules.calculate() + %d; }
            }
            """.formatted(n); }
    private static final String COUNTERS = "package app;\npublic class Counters { public static int descriptions; }";
    private static final String CATALOG = """
            package app;
            @org.springframework.stereotype.Service
            public class CatalogService {
                @org.springframework.cache.annotation.Cacheable("descriptions")
                public String value() { Counters.descriptions++; return "description"; }
            }
            """;
    private static final String QUOTE = """
            package app;
            @org.springframework.stereotype.Service
            public class QuoteService {
                @org.springframework.beans.factory.annotation.Autowired PriceService prices;
                @org.springframework.cache.annotation.Cacheable("quotes")
                public int value() { return prices.value(); }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.cache.annotation.EnableCaching;
            @Configuration @EnableCaching @ComponentScan("app")
            public class App {
                @Bean public org.springframework.cache.CacheManager cacheManager() {
                    return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("prices", "quotes", "descriptions");
                }
                static String read(AnnotationConfigApplicationContext c) {
                    return c.getBean(PriceService.class).value() + ":" + c.getBean(QuoteService.class).value()
                        + ":" + c.getBean(CatalogService.class).value() + ":" + Counters.descriptions;
                }
                public static void main(String[] args) throws Exception {
                    var c = new AnnotationConfigApplicationContext(App.class);
                    System.out.println("WARM=" + read(c));
                    Path dir = Path.of(System.getProperty("test.dir"));
                    while (!Files.exists(dir.resolve("probe1"))) Thread.sleep(10);
                    System.out.println("FIRST=" + read(c));
                    while (!Files.exists(dir.resolve("probe2"))) Thread.sleep(10);
                    System.out.println("SECOND=" + read(c));
                    Thread.sleep(60000);
                }
            }
            """;
}
