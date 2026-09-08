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

import static org.junit.jupiter.api.Assertions.*;

class AddedScheduledMethodReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void addedTaskRunsOncePerRegistrationFollowsEditsAndStopsOnRemoval(boolean withOriginal) throws Exception {
        int baseline = withOriginal ? 1 : 0;
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dtest.dir=" + tmp, "-Dtest.original=" + withOriginal)
                .with("App", APP).with("Jobs", jobs(0, false, false))
                .with("OriginalJob", withOriginal ? ORIGINAL : ORIGINAL.replace(
                        "@org.springframework.scheduling.annotation.Scheduled(fixedDelay=25)", ""))
                .with("Counters", COUNTERS).start()) {
            app.awaitOrFail("READY", "Spring did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, baseline, 0);

            reload(app, jobs(2, true, true), 1);
            probe(app, 1, baseline + 1, 2);
            reload(app, jobs(3, true, true), 2);
            probe(app, 2, baseline + 1, 3);
            reload(app, jobs(4, true, true), 3);
            probe(app, 3, baseline + 1, 4);
            // The method remains, but it is no longer scheduled.
            reload(app, jobs(5, true, false), 4);
            probe(app, 4, baseline, -1);
            reload(app, jobs(6, true, true), 5);
            probe(app, 5, baseline + 1, 6);
            // Removed companion methods can keep their implementation for old
            // callers. The scheduler must nevertheless stop calling this one.
            reload(app, jobs(7, false, false), 6);
            probe(app, 6, baseline, -1);
            reload(app, jobs(8, true, true), 7);
            probe(app, 7, baseline + 1, 8);
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=0:stable", "closing Spring must cancel the added task");
            assertTrue(app.output().contains("BEAN-REPLACED"), "the external bean replacement must run");
            assertFalse(app.output().contains("STALE-BEAN"), "the task must call the current singleton: " + app.tail());
            System.out.println("[added-scheduled] CLOSED=0:stable; current singleton at every stage");
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Jobs.added() was added")),
                    "a scheduled method that works must not be reported as invisible: " + app.tail());
        }
    }

    private void reload(WatchedApp app, String source, int expectedReloads) throws Exception {
        app.rewrite("Jobs", source);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && app.output().stream()
                .filter(AddedScheduledMethodReloadTest::isReload).count() < expectedReloads) Thread.sleep(25);
        assertEquals(expectedReloads, app.output().stream()
                .filter(AddedScheduledMethodReloadTest::isReload).count(), app.tail());
    }

    private static boolean isReload(String line) {
        return line.contains("Reloaded app.Jobs") || line.contains("Structural reload: app.Jobs");
    }

    private void probe(WatchedApp app, int stage, int tasks, int value) throws Exception {
        Files.writeString(tmp.resolve("probe" + stage), Integer.toString(value));
        app.awaitOrFail("PROBE" + stage + "=", "probe did not run");
        String actual = app.latest("PROBE" + stage + "=");
        System.out.println("[added-scheduled] " + actual);
        assertEquals("PROBE" + stage + "=" + tasks + ":" + (value < 0 ? "stopped" : value)
                + ":original-ok", actual, app.tail());
    }

    private static String jobs(int version, boolean method, boolean annotation) {
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Jobs {
                    public int version() { return %d; }
                    %s
                }
                """.formatted(version, method
                ? (annotation ? "@org.springframework.scheduling.annotation.Scheduled(fixedDelayString=\"${tick.delay:25}\")\n" : "")
                    + "private void added() { Counters.lastJob = this; Counters.value = " + version + "; Counters.added.incrementAndGet(); }"
                : "");
    }

    private static final String COUNTERS = """
            package app;
            public class Counters {
                public static volatile int value;
                public static volatile Object lastJob;
                public static final java.util.concurrent.atomic.AtomicInteger added = new java.util.concurrent.atomic.AtomicInteger();
                public static final java.util.concurrent.atomic.AtomicInteger original = new java.util.concurrent.atomic.AtomicInteger();
            }
            """;
    private static final String ORIGINAL = """
            package app;
            @org.springframework.stereotype.Service
            public class OriginalJob {
                @org.springframework.scheduling.annotation.Scheduled(fixedDelay=25)
                public void tick() { Counters.original.incrementAndGet(); }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.annotation.*;
            import org.springframework.scheduling.annotation.*;
            @Configuration @EnableScheduling @ComponentScan("app")
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext(App.class);
                    var processor = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
                    boolean original = Boolean.getBoolean("test.original");
                    Path dir = Path.of(System.getProperty("test.dir"));
                    System.out.println("READY");
                    for (int stage = 0; stage < 8; stage++) {
                        Path probe = dir.resolve("probe" + stage);
                        while (!Files.exists(probe)) Thread.sleep(10);
                        int expected = Integer.parseInt(Files.readString(probe));
                        if (stage == 3) {
                            Object old = context.getBean(Jobs.class);
                            context.getDefaultListableBeanFactory().destroySingleton("jobs");
                            Object replacement = context.getBean(Jobs.class);
                            if (old != replacement) System.out.println("BEAN-REPLACED");
                        }
                        long deadline = System.nanoTime() + 2_000_000_000L;
                        while (expected >= 0 && Counters.value != expected && System.nanoTime() < deadline) Thread.sleep(10);
                        // Cancellation follows the bytecode switch. An invocation
                        // can run during that switch; what must stop is future
                        // execution after the completed reload, not its last value.
                        Thread.sleep(100);
                        int before = Counters.original.get();
                        int addedBefore = Counters.added.get();
                        Thread.sleep(150);
                        int tasks = processor.getScheduledTasks().size();
                        boolean currentBean = tasks == (original ? 1 : 0) || Counters.lastJob == context.getBean(Jobs.class);
                        boolean originalOk = original ? Counters.original.get() > before : Counters.original.get() == 0;
                        String observed = expected < 0 ? (Counters.added.get() == addedBefore ? "stopped" : "still-running")
                            : Integer.toString(Counters.value);
                        System.out.println("PROBE" + stage + "=" + tasks
                            + ":" + observed + ":" + (originalOk ? "original-ok" : "original-wrong"));
                        if (!currentBean) System.out.println("STALE-BEAN");
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close();
                    Thread.sleep(100);
                    int before = Counters.added.get();
                    Thread.sleep(150);
                    System.out.println("CLOSED=" + processor.getScheduledTasks().size()
                        + ":" + (Counters.added.get() == before ? "stable" : "still-running"));
                }
            }
            """;
}
