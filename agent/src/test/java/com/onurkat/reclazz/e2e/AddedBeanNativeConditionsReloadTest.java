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

class AddedBeanNativeConditionsReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void conditionalFactoriesFollowActivationErrorsRemovalAndLazyRestoration(boolean child, boolean full) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Config", config(0, full))
                .with("Product", PRODUCT).with("Gate", GATE).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child loader missing");
            String[] expected = {
                    "present=false alias=false made=0 closed=0 version=-1 originalMethod=false",
                    "present=true alias=true made=1 closed=0 version=2 originalMethod=false",
                    "present=false alias=false made=1 closed=1 version=-1 originalMethod=false",
                    "present=true alias=true made=2 closed=1 version=4 originalMethod=false",
                    "present=false alias=false made=2 closed=2 version=-1 originalMethod=false",
                    "present=false alias=false made=2 closed=2 version=-1 originalMethod=false",
                    "present=true alias=true made=3 closed=2 version=7 originalMethod=false"
            };
            for (int stage = 1; stage <= 7; stage++) {
                app.rewrite("Config", config(stage, full));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
                assertEquals(stage, reloads(app), app.tail());
                Files.createFile(tmp.resolve("probe" + stage));
                app.awaitOrFail("R" + stage + "=", "probe did not complete");
                assertEquals("R" + stage + "=" + expected[stage - 1], app.latest("R" + stage + "="), app.tail());
                System.out.println("[added-bean-conditions] child=" + child + " full=" + full + " " + app.latest("R" + stage + "="));
            }
            assertTrue(app.output().stream().anyMatch(s -> s.contains("fixture condition failure")), app.tail());
            assertEquals("BEFORE_LAZY=2", app.latest("BEFORE_LAZY="), app.tail());
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=3 CONDITIONS=6", "destruction or condition phase/count differs");
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Config") || s.contains("Structural reload: app.Config")).count();
    }

    private static String config(int version, boolean full) {
        String condition = switch (version) { case 1, 6 -> "Disabled"; case 3 -> "Failure"; default -> "Enabled"; };
        String factory = version == 0 || version == 5 ? "" : """
                @Bean({"client", "alias"}) @Conditional(Gate.%s.class) %s
                public Product client() { return new Product(%d); }
                """.formatted(condition, version == 7 ? "@Lazy" : "", version);
        return """
                package app;
                import org.springframework.context.annotation.*;
                @Configuration(proxyBeanMethods=%s)
                public class Config {
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

    private static final String GATE = """
            package app;
            import org.springframework.context.annotation.*;
            import org.springframework.core.type.*;
            public class Gate {
                public static int calls;
                static void check(ConditionContext c, AnnotatedTypeMetadata metadata) {
                    calls++;
                    if (!(metadata instanceof MethodMetadata m)
                        || !m.getDeclaringClassName().equals("app.Config") || !m.getMethodName().equals("client")
                        || !m.getReturnTypeName().equals("app.Product") || m.isStatic()
                        || !c.getRegistry().containsBeanDefinition("marker")
                        || c.getBeanFactory() != App.context.getBeanFactory()
                        || c.getClassLoader() != App.class.getClassLoader()
                        || !c.getResourceLoader().getResource("fixture:enabled").exists()
                        || !"yes".equals(c.getEnvironment().getProperty("feature.enabled")))
                        throw new IllegalStateException("incorrect saved factory metadata or application context");
                }
                public static class Enabled implements Condition {
                    public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) { check(c,m); return true; }
                }
                public static class Disabled implements Condition {
                    public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) { check(c,m); return false; }
                }
                public static class Failure implements Condition {
                    public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
                        check(c,m); throw new IllegalStateException("fixture condition failure");
                    }
                }
            }
            """;

    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.util.*;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            import org.springframework.core.env.MapPropertySource;
            import org.springframework.core.io.ByteArrayResource;
            public class App {
                public static AnnotationConfigApplicationContext context;
                public static void main(String[] args) throws Exception {
                    try (var c = new AnnotationConfigApplicationContext()) {
                        context = c;
                        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("feature.enabled", "yes")));
                        c.addProtocolResolver((location, loader) -> location.equals("fixture:enabled") ? new ByteArrayResource(new byte[]{1}) : null);
                        c.registerBean("marker", String.class, () -> "marker");
                        c.register(Config.class);
                        c.refresh();
                        Path dir = Path.of(System.getProperty("test.dir"));
                        System.out.println("READY");
                        for (int stage = 1; stage <= 7; stage++) {
                            while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                            if (stage == 7) System.out.println("BEFORE_LAZY=" + Product.made);
                            boolean present = c.containsBean("client");
                            Product product = present ? c.getBean("client", Product.class) : null;
                            boolean alias = c.isAlias("alias");
                            if (alias && c.getBean("alias") != product) throw new IllegalStateException("alias differs from singleton");
                            System.out.println("R" + stage + "=present=" + present + " alias=" + alias
                                + " made=" + Product.made + " closed=" + Product.closed + " version=" + (product == null ? -1 : product.version)
                                + " originalMethod=" + Arrays.stream(Config.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("client")));
                        }
                        while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    }
                    System.out.println("CLOSED=" + Product.closed + " CONDITIONS=" + Gate.calls);
                }
            }
            """;
}
