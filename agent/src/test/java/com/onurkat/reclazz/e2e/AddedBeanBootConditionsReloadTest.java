/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AddedBeanBootConditionsReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void bootConditionsEvaluateHiddenFactoriesAndRetireTheirProducts(boolean child, boolean full) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Config", config(0, full)).with("Product", PRODUCT).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            String[] expected = {
                    "present=false alias=false made=0 closed=0 version=-1 originalMethod=false control=true",
                    "present=true alias=true made=1 closed=0 version=2 originalMethod=false control=true",
                    "present=true alias=true made=2 closed=1 version=3 originalMethod=false control=true",
                    "present=true alias=true made=3 closed=2 version=4 originalMethod=false control=true",
                    "present=false alias=false made=3 closed=3 version=-1 originalMethod=false control=true",
                    "present=false alias=false made=3 closed=3 version=-1 originalMethod=false control=true",
                    "present=true alias=true made=4 closed=3 version=7 originalMethod=false control=true"
            };
            for (int stage = 1; stage <= 7; stage++) {
                app.rewrite("Config", config(stage, full));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
                assertEquals(stage, reloads(app), app.tail());
                Files.createFile(tmp.resolve("probe" + stage));
                app.awaitOrFail("R" + stage + "=", "probe did not complete");
                assertEquals("R" + stage + "=" + expected[stage - 1], app.latest("R" + stage + "="), app.tail());
                System.out.println("[added-bean-boot] child=" + child + " full=" + full + " " + app.latest("R" + stage + "="));
            }
            assertEquals("BEFORE_LAZY=3", app.latest("BEFORE_LAZY="), app.tail());
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=4", "owned products were not destroyed exactly once");
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Config") || s.contains("Structural reload: app.Config")).count();
    }
    private static String config(int version, boolean full) {
        String condition = switch (version) {
            case 1 -> "@ConditionalOnProperty(name=\"feature.enabled\", havingValue=\"false\")";
            case 2 -> "@ConditionalOnProperty(prefix=\"feature\", name=\"enabled\", havingValue=\"true\")";
            case 4 -> "@ConditionalOnBean(String.class) @Profile(\"enabled\")";
            case 5 -> "@ConditionalOnMissingBean(name=\"marker\")";
            default -> "@ConditionalOnMissingBean";
        };
        String factory = version == 0 || version == 6 ? "" : """
                @Bean({"client", "alias"}) %s %s
                public Product client() { return new Product(%d); }
                """.formatted(condition, version == 7 ? "@Lazy" : "", version);
        return """
                package app;
                import org.springframework.context.annotation.*;
                import org.springframework.boot.autoconfigure.condition.*;
                @Configuration(proxyBeanMethods=%s)
                public class Config {
                    @Bean @ConditionalOnProperty("feature.enabled")
                    public String control() { return "control"; }
                    public int version() { return %d; }
                    %s
                }
                """.formatted(full, version, factory);
    }
    private static final String PRODUCT = """
            package app;
            public class Product implements AutoCloseable {
                public static int made, closed;
                public final int version;
                public Product(int version) { this.version = version; made++; }
                public void close() { closed++; }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.util.*;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            import org.springframework.core.env.MapPropertySource;
            public class App {
                public static void main(String[] args) throws Exception {
                    try (var c = new AnnotationConfigApplicationContext()) {
                        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("feature.enabled", "true")));
                        c.getEnvironment().setActiveProfiles("enabled");
                        c.registerBean("marker", String.class, () -> "marker");
                        c.register(Config.class); c.refresh();
                        Object control = c.getBean("control");
                        Path dir = Path.of(System.getProperty("test.dir"));
                        System.out.println("READY");
                        for (int stage = 1; stage <= 7; stage++) {
                            while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                            if (stage == 7) System.out.println("BEFORE_LAZY=" + Product.made);
                            boolean present = c.containsBean("client");
                            Product product = present ? c.getBean("client", Product.class) : null;
                            boolean alias = c.isAlias("alias");
                            if (alias && c.getBean("alias") != product) throw new IllegalStateException("alias differs from singleton");
                            if (!c.getBean("marker").equals("marker")) throw new IllegalStateException("external bean changed");
                            System.out.println("R" + stage + "=present=" + present + " alias=" + alias
                                + " made=" + Product.made + " closed=" + Product.closed + " version=" + (product == null ? -1 : product.version)
                                + " originalMethod=" + Arrays.stream(Config.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("client"))
                                + " control=" + (control == c.getBean("control")));
                        }
                        while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    }
                    System.out.println("CLOSED=" + Product.closed);
                }
            }
            """;
}
