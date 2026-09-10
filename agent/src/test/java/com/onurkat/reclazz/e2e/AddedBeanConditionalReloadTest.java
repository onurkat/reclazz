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

class AddedBeanConditionalReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lazyPrototypeAndProfileMetadataOnAddedFactoriesFollowSavesAndScopeLifecycle(boolean child) throws Exception {
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Config", config(0, false))
                .with("Held", HELD).with("Proto", PROTO).with("Marker", MARKER).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "fixture did not cross a module boundary");

            // Registration, profile gate, and lazy not created until first access.
            reload(app, 1, false); probe(app, 1, "has-lazy=true has-proto=true prod=true dev=false held-made=0 proto-made=0");
            // Profiles swapped on edit: the prod bean leaves, the dev bean joins.
            reload(app, 2, true); probe(app, 2, "has-lazy=true has-proto=true prod=false dev=true held-made=0 proto-made=0");
            // Swapped back, then first access: lazy singleton is created once and
            // keeps identity; prototype hands out a distinct instance each call.
            reload(app, 3, false); probe(app, 3, "prod=true dev=false held-id=stable held-made=1 proto-distinct=true proto-made=2");

            Files.createFile(tmp.resolve("close"));
            // The lazy singleton was created, so the context destroys it once.
            // A prototype is not tracked, so Spring never destroys it.
            app.awaitOrFail("CLOSED held=1 proto=0", "scope destruction did not match");
            System.out.println("[bean-conditional] child=" + child + " CLOSED held=1 proto=0");
        }
    }

    private void reload(WatchedApp app, int stage, boolean swap) throws Exception {
        app.rewrite("Config", config(stage, swap));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
        assertEquals(stage, reloads(app), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Config") || s.contains("Structural reload: app.Config")).count();
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("R" + stage + "=", "probe did not run");
        String observed = app.latest("R" + stage + "=");
        System.out.println("[bean-conditional] " + observed);
        assertEquals("R" + stage + "=" + expected, observed, app.tail());
    }

    private static String config(int version, boolean swap) {
        String prodProfile = swap ? "dev" : "prod";
        String devProfile = swap ? "prod" : "dev";
        String factories = version == 0 ? "" : """
                @Bean @Lazy public Held heldBean() { return new Held(); }
                @Bean @Scope("prototype") public Proto protoBean() { return new Proto(); }
                @Bean @Profile("%s") public Marker prodBean() { return new Marker("prod"); }
                @Bean @Profile("%s") public Marker devBean() { return new Marker("dev"); }
                """.formatted(prodProfile, devProfile);
        return """
                package app;
                import org.springframework.context.annotation.*;
                @Configuration(proxyBeanMethods=false)
                public class Config {
                    public int version() { return %d; }
                    %s
                }
                """.formatted(version, factories);
    }
    private static final String HELD = """
            package app;
            public class Held implements AutoCloseable {
                public static int made, closed;
                public Held() { made++; }
                public void close() { closed++; }
            }
            """;
    private static final String PROTO = """
            package app;
            public class Proto implements AutoCloseable {
                public static int made, closed;
                public Proto() { made++; }
                public void close() { closed++; }
            }
            """;
    private static final String MARKER = """
            package app;
            public class Marker {
                public final String name;
                public Marker(String name) { this.name = name; }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.AnnotationConfigApplicationContext;
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext();
                    context.getEnvironment().setActiveProfiles("prod");
                    context.register(Config.class);
                    context.refresh();
                    Path dir = Path.of(System.getProperty("test.dir"));
                    System.out.println("READY");
                    await(dir, 1);
                    System.out.println("R1=has-lazy=" + context.containsBean("heldBean")
                        + " has-proto=" + context.containsBean("protoBean")
                        + " prod=" + context.containsBean("prodBean")
                        + " dev=" + context.containsBean("devBean")
                        + " held-made=" + Held.made + " proto-made=" + Proto.made);
                    await(dir, 2);
                    System.out.println("R2=has-lazy=" + context.containsBean("heldBean")
                        + " has-proto=" + context.containsBean("protoBean")
                        + " prod=" + context.containsBean("prodBean")
                        + " dev=" + context.containsBean("devBean")
                        + " held-made=" + Held.made + " proto-made=" + Proto.made);
                    await(dir, 3);
                    Object heldA = context.getBean("heldBean");
                    Object heldB = context.getBean("heldBean");
                    Object protoA = context.getBean("protoBean");
                    Object protoB = context.getBean("protoBean");
                    System.out.println("R3=prod=" + context.containsBean("prodBean")
                        + " dev=" + context.containsBean("devBean")
                        + " held-id=" + (heldA == heldB ? "stable" : "changed")
                        + " held-made=" + Held.made
                        + " proto-distinct=" + (protoA != protoB)
                        + " proto-made=" + Proto.made);
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close();
                    System.out.println("CLOSED held=" + Held.closed + " proto=" + Proto.closed);
                }
                private static void await(Path dir, int stage) throws Exception {
                    while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                }
            }
            """;
}
