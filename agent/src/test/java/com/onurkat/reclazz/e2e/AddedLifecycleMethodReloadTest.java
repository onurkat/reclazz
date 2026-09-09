/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddedLifecycleMethodReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void addedCallbacksFollowInstanceLifetimeAcrossSaves(boolean original) throws Exception {
        String annotations = Path.of(javax.annotation.PostConstruct.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        List<String> events = new ArrayList<>();
        init(events, 1, 0, original);
        try (var app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + annotations)
                .jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Counters", COUNTERS)
                .with("Service", service(0, false, false, original)).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, events, 0);
            int previous = 0;
            for (int stage = 1; stage <= 6; stage++) {
                boolean annotated = stage != 3 && stage != 5;
                app.rewrite("Service", service(stage, stage != 3, annotated, original));
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (reloads(app) < stage && System.nanoTime() < deadline) Thread.sleep(25);
                assertEquals(stage, reloads(app), app.tail());
                destroy(events, stage, previous, original);
                init(events, stage + 1, annotated ? stage : 0, original);
                previous = annotated ? stage : 0;
                probe(app, stage, events, annotated ? 1 : 0);
            }
            // Ordinary factory recreation must also honor the installed lifecycle.
            destroy(events, 7, 6, original);
            init(events, 8, 6, original);
            probe(app, 7, events, 1);
            destroy(events, 8, 6, original);
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=", "context did not close");
            assertEquals("CLOSED=" + String.join(",", events) + "|open=0", app.latest("CLOSED="), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Service.addedInit() was added")
                    || s.contains("Service.addedDestroy() was added")), app.tail());
            System.out.println("[added-lifecycle] original=" + original + " " + app.latest("CLOSED="));
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Service")
                || s.contains("Structural reload: app.Service")).count();
    }

    private void probe(WatchedApp app, int stage, List<String> expected, int open) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("PROBE" + stage + "=", "lifecycle probe did not run");
        assertEquals("PROBE" + stage + "=" + String.join(",", expected) + "|open=" + open,
                app.latest("PROBE" + stage + "="), app.tail());
    }

    private static void init(List<String> events, int id, int version, boolean original) {
        if (original) events.add("original-init:" + id);
        if (version > 0) events.add("added-init:" + id + ":" + version + ":injected");
        events.add("after:" + id);
    }

    private static void destroy(List<String> events, int id, int version, boolean original) {
        if (original) events.add("original-destroy:" + id);
        if (version > 0) events.add("added-destroy:" + id + ":" + version);
        events.add("disposable:" + id);
    }

    private static String service(int version, boolean methods, boolean annotated, boolean original) {
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Service implements org.springframework.beans.factory.InitializingBean,
                        org.springframework.beans.factory.DisposableBean {
                    final int id = ++Counters.next;
                    @org.springframework.beans.factory.annotation.Autowired String token;
                    %s
                    public int version() { return %d; }
                    public void afterPropertiesSet() { Counters.events.add("after:" + id); }
                    public void destroy() { Counters.events.add("disposable:" + id); }
                    %s
                    %s
                }
                """.formatted(original || version > 0 ? "java.nio.channels.Pipe.SourceChannel resource;" : "", version, original ? """
                @javax.annotation.PostConstruct private void originalInit() {
                    Counters.events.add("original-init:" + id);
                }
                @javax.annotation.PreDestroy private void originalDestroy() {
                    Counters.events.add("original-destroy:" + id);
                }
                """ : "", methods ? (annotated ? "@javax.annotation.PostConstruct\n" : "") + """
                private void addedInit() throws Exception {
                    if (!"injected".equals(token)) throw new IllegalStateException("not injected");
                    var pipe = java.nio.channels.Pipe.open();
                    pipe.sink().close(); resource = pipe.source();
                    Counters.channels.add(resource);
                    Counters.events.add("added-init:" + id + ":%d:" + token);
                }
                """.formatted(version) + (annotated ? "@javax.annotation.PreDestroy\n" : "") + """
                private void addedDestroy() throws Exception {
                    if (resource == null || !resource.isOpen()) throw new IllegalStateException("double or retroactive destroy");
                    resource.close();
                    Counters.events.add("added-destroy:" + id + ":%d");
                }
                """.formatted(version) : "");
    }

    private static final String COUNTERS = """
            package app;
            public class Counters {
                public static int next;
                public static final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
                public static final java.util.List<java.nio.channels.Pipe.SourceChannel> channels = new java.util.concurrent.CopyOnWriteArrayList<>();
                public static String state() {
                    return String.join(",", events) + "|open=" + channels.stream().filter(c -> c.isOpen()).count();
                }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            @Configuration(proxyBeanMethods=false) @ComponentScan("app")
            public class App {
                @Bean String token() { return "injected"; }
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext(App.class);
                    context.getBean(Service.class).version();
                    Path dir = Path.of(System.getProperty("test.dir"));
                    System.out.println("READY");
                    for (int stage = 0; stage <= 7; stage++) {
                        while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                        if (stage == 7) {
                            context.getDefaultListableBeanFactory().destroySingleton("service");
                            context.getBean(Service.class);
                        }
                        System.out.println("PROBE" + stage + "=" + Counters.state());
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close();
                    System.out.println("CLOSED=" + Counters.state());
                }
            }
            """;
}
