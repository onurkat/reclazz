/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AddedEventListenerResultReloadTest {
    @TempDir Path tmp;
    @Test
    void returnedEventsFollowBodyConditionAndRemovalEdits() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("Listeners", listeners(0, false, false, "go", "value"))
                .with("Receiver", RECEIVER).with("Counters", COUNTERS).with("Reply", REPLY).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, 0, 0, 0, 0);
            reload(app, 1, listeners(2, true, true, "go", "value")); probe(app, 1, 1, 1, 2, 0);
            reload(app, 2, listeners(3, true, true, "go", "value")); probe(app, 2, 1, 1, 3, 0);
            reload(app, 3, listeners(4, true, true, "never", "value")); probe(app, 3, 0, 0, 0, 0);
            reload(app, 4, listeners(5, true, true, "go", "null")); probe(app, 4, 1, 0, 0, 0);
            reload(app, 5, listeners(6, true, true, "go", "fail")); probe(app, 5, 1, 0, 0, 1);
            reload(app, 6, listeners(7, true, true, "go", "value")); probe(app, 6, 1, 1, 7, 0);
            reload(app, 7, listeners(8, true, false, "go", "value")); probe(app, 7, 0, 0, 0, 0);
            reload(app, 8, listeners(9, true, true, "go", "value")); probe(app, 8, 1, 1, 9, 0);
            reload(app, 9, listeners(10, false, false, "go", "value")); probe(app, 9, 0, 0, 0, 0);
            assertTrue(app.output().contains("BEAN-REPLACED"), app.tail());
        }
    }
    private void reload(WatchedApp app, int stage, String source) throws Exception {
        app.rewrite("Listeners", source);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
        assertEquals(stage, reloads(app), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listeners") || s.contains("Structural reload: app.Listeners")).count();
    }
    private void probe(WatchedApp app, int stage, int calls, int received, int value, int failures) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("RESULT" + stage + "=", "probe did not complete");
        String observed = app.latest("RESULT" + stage + "=");
        System.out.println("[event-result] " + observed);
        assertEquals("RESULT" + stage + "=" + calls + ":" + received + ":" + value + ":" + failures + ":2:identity-ok", observed, app.tail());
    }
    private static String listeners(int version, boolean method, boolean annotation, String condition, String mode) {
        String body = switch (mode) {
            case "null" -> "return null;";
            case "fail" -> "throw new IllegalArgumentException(\"expected handler failure\");";
            default -> "Reply reply = new Reply(" + version + "); Counters.produced = reply; return reply;";
        };
        String callback = method ? (annotation ? "@org.springframework.context.event.EventListener(condition=\"#a0 == '" + condition + "'\")\n" : "")
                + "private Reply added(String event) { Counters.calls++; Counters.bean = this; " + body + " }" : "";
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Listeners {
                    public int version() { return %d; }
                    %s
                }
                """.formatted(version, callback);
    }
    private static final String REPLY = """
            package app;
            public record Reply(int value) { }
            """;
    private static final String COUNTERS = """
            package app;
            public class Counters {
                public static int calls, received, other, value;
                public static Object produced, observed, bean;
            }
            """;
    private static final String RECEIVER = """
            package app;
            @org.springframework.stereotype.Service
            public class Receiver {
                @org.springframework.context.event.EventListener
                public void reply(Reply reply) { Counters.received++; Counters.value = reply.value(); Counters.observed = reply; }
                @org.springframework.context.event.EventListener
                @org.springframework.core.annotation.Order(-100)
                public void request(String request) { Counters.other++; }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            @Configuration @ComponentScan("app")
            public class App {
                public static void main(String[] args) throws Exception {
                    try (var context = new AnnotationConfigApplicationContext(App.class)) {
                        Path dir = Path.of(System.getProperty("test.dir"));
                        System.out.println("READY");
                        for (int stage = 0; stage < 10; stage++) {
                            while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                            if (stage == 2) {
                                Object old = context.getBean(Listeners.class);
                                context.getDefaultListableBeanFactory().destroySingleton("listeners");
                                if (old != context.getBean(Listeners.class)) System.out.println("BEAN-REPLACED");
                            }
                            Counters.calls = Counters.received = Counters.other = Counters.value = 0;
                            Counters.produced = Counters.observed = Counters.bean = null;
                            int failures = 0;
                            for (String request : new String[]{"go", "skip"}) {
                                try { context.publishEvent(request); }
                                catch (IllegalArgumentException failure) {
                                    if (!"expected handler failure".equals(failure.getMessage())) throw failure;
                                    failures++;
                                }
                            }
                            boolean identity = Counters.produced == Counters.observed &&
                                (Counters.calls == 0 || Counters.bean == context.getBean(Listeners.class));
                            System.out.println("RESULT" + stage + "=" + Counters.calls + ":" + Counters.received
                                + ":" + Counters.value + ":" + failures + ":" + Counters.other
                                + (identity ? ":identity-ok" : ":wrong-identity"));
                        }
                    }
                }
            }
            """;
}
