/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AddedEventListenerMethodReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void addedListenerFiltersRunsOnceFollowsReplacementAndStopsOnRemoval(boolean withOriginal) throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dtest.dir=" + tmp, "-Dtest.original=" + withOriginal)
                .with("App", APP).with("Listeners", listeners(0, false, false, withOriginal, "go"))
                .with("Other", OTHER).with("Counters", COUNTERS).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, 0, 0, withOriginal);
            reload(app, listeners(2, true, true, withOriginal, "go"), 1);
            probe(app, 1, 2, 2, withOriginal);
            reload(app, listeners(3, true, true, withOriginal, "go"), 2);
            probe(app, 2, 2, 3, withOriginal);
            reload(app, listeners(4, true, true, withOriginal, "no"), 3);
            probe(app, 3, 1, 4, withOriginal);
            reload(app, listeners(5, true, false, withOriginal, "go"), 4);
            probe(app, 4, 0, 0, withOriginal);
            reload(app, listeners(6, true, true, withOriginal, "go"), 5);
            probe(app, 5, 2, 6, withOriginal);
            reload(app, listeners(7, false, false, withOriginal, "go"), 6);
            probe(app, 6, 0, 0, withOriginal);
            reload(app, listeners(8, true, true, withOriginal, "go"), 7);
            probe(app, 7, 2, 8, withOriginal);
            reload(app, listeners(9, false, false, false, "go"), 8);
            probe(app, 8, 0, 0, false);
            assertTrue(app.output().contains("BEAN-REPLACED"));
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Listeners.added() was added")), app.tail());
        }
    }

    private void reload(WatchedApp app, String source, int expected) throws Exception {
        app.rewrite("Listeners", source);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < expected) Thread.sleep(25);
        assertEquals(expected, reloads(app), app.tail());
    }

    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listeners")
                || s.contains("Structural reload: app.Listeners")).count();
    }

    private void probe(WatchedApp app, int stage, int calls, int value, boolean original) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("PROBE" + stage + "=", "event probe did not complete");
        String actual = app.latest("PROBE" + stage + "=");
        System.out.println("[added-event] " + actual);
        assertEquals("PROBE" + stage + "=" + calls + ":" + value + ":3:"
                + (original ? 3 : 0) + ":current", actual, app.tail());
    }

    private static String listeners(int version, boolean method, boolean annotation, boolean original, String filter) {
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Listeners {
                    public int version() { return %d; }
                    %s
                    %s
                }
                """.formatted(version, original ? "@org.springframework.context.event.EventListener public void old(String e) { Counters.original++; }" : "",
                method ? (annotation ? "@org.springframework.context.event.EventListener(classes=String.class, condition=\"#a0 == '" + filter + "'\")\n" : "")
                        + "private void added(String event) { Counters.last = this; Counters.value = " + version + "; Counters.added++; }" : "");
    }

    private static final String COUNTERS = """
            package app;
            public class Counters {
                public static int added, other, original, value;
                public static Object last;
            }
            """;
    private static final String OTHER = """
            package app;
            @org.springframework.stereotype.Service
            public class Other {
                @org.springframework.context.event.EventListener
                public void on(String event) { Counters.other++; }
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
                        for (int stage = 0; stage < 9; stage++) {
                            while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                            if (stage == 3) {
                                Object old = context.getBean(Listeners.class);
                                context.getDefaultListableBeanFactory().destroySingleton("listeners");
                                if (old != context.getBean(Listeners.class)) System.out.println("BEAN-REPLACED");
                            }
                            Counters.added = Counters.other = Counters.original = Counters.value = 0;
                            Counters.last = null;
                            context.publishEvent("go");
                            context.publishEvent("no");
                            context.publishEvent(123);
                            context.publishEvent("go");
                            System.out.println("PROBE" + stage + "=" + Counters.added + ":" + Counters.value
                                + ":" + Counters.other + ":" + Counters.original + ":"
                                + (Counters.added == 0 || Counters.last == context.getBean(Listeners.class) ? "current" : "stale"));
                        }
                    }
                }
            }
            """;
}
