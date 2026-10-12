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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExistingScheduledMetadataReloadTest {
    @TempDir Path tmp;

    @Test
    void existingTimerUsesSavedIntervalAndCurrentSingletonWithoutRetiredTicks() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=100").jvmArgs("-Dprobe.dir=" + tmp)
                .with("App", APP).with("Jobs", jobs(1, 25)).with("Tape", TAPE).start()) {
            app.awaitOrFail("READY", "context did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, 25, 1);
            int[] delays = {40, 60, 25};
            for (int stage = 1; stage <= 3; stage++) {
                app.rewrite("Jobs", jobs(stage + 1, delays[stage - 1]));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
                assertEquals(stage, reloads(app), app.tail());
                probe(app, stage, delays[stage - 1], stage + 1);
            }
            Files.createFile(tmp.resolve("close"));
            app.awaitOrFail("CLOSED=0:true", "context close must cancel future ticks");
            assertFalse(app.output().stream().anyMatch(line -> line.contains("@Scheduled reload")
                    && line.contains("could not apply")), app.tail());
        }
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(line -> line.contains("Reloaded app.Jobs ")
                || line.contains("Structural reload: app.Jobs ")).count();
    }
    private void probe(WatchedApp app, int stage, int delay, int version) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("P" + stage + "=", "timer probe missing");
        String observed = app.latest("P" + stage + "=");
        assertEquals("P" + stage + "=1:" + delay + ":" + version + ":true:true", observed, app.tail());
        System.out.println("[existing-scheduled] " + observed);
    }
    private static String jobs(int version, int delay) {
        return """
                package app;
                @org.springframework.stereotype.Service
                public class Jobs {
                    public final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=%d)
                    public void tick() { calls.incrementAndGet(); Tape.last=this; Tape.version=%d; }
                }
                """.formatted(delay, version);
    }
    private static final String TAPE = """
            package app;
            public class Tape { public static volatile Object last; public static volatile int version; }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.util.*;
            import org.springframework.context.annotation.*;
            import org.springframework.scheduling.annotation.*;
            @Configuration(proxyBeanMethods=false) @EnableScheduling @ComponentScan("app")
            public class App {
                public static void main(String[] args) throws Exception {
                    var context = new AnnotationConfigApplicationContext(App.class);
                    var scheduler = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
                    Path dir = Path.of(System.getProperty("probe.dir"));
                    List<Jobs> retired = new ArrayList<>(); Jobs previous = null;
                    System.out.println("READY");
                    for (int stage=0; stage<=3; stage++) {
                        while (!Files.exists(dir.resolve("probe"+stage))) Thread.sleep(10);
                        Jobs current = context.getBean(Jobs.class);
                        if (previous != null) {
                            if (previous == current) throw new AssertionError("singleton not recreated");
                            retired.add(previous);
                        }
                        long deadline = System.nanoTime()+2_000_000_000L;
                        while ((Tape.last != current || current.calls.get()==0) && System.nanoTime()<deadline) Thread.sleep(10);
                        // A completed short tick may race cancellation; measure future ticks after settling.
                        Thread.sleep(150);
                        List<Integer> before = retired.stream().map(j->j.calls.get()).toList();
                        int activeBefore = current.calls.get(); Thread.sleep(180);
                        boolean stable = before.equals(retired.stream().map(j->j.calls.get()).toList());
                        var tasks = scheduler.getScheduledTasks();
                        long interval = tasks.size()==1 && tasks.iterator().next().getTask()
                            instanceof org.springframework.scheduling.config.FixedDelayTask delay ? delay.getInterval() : -1;
                        boolean active = Tape.last==current && current.calls.get()>activeBefore;
                        System.out.println("P"+stage+"="+tasks.size()+":"+interval+":"+Tape.version+":"+active+":"+stable);
                        previous=current;
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(10);
                    context.close(); retired.add(previous); Thread.sleep(150);
                    List<Integer> before = retired.stream().map(j->j.calls.get()).toList(); Thread.sleep(180);
                    System.out.println("CLOSED="+scheduler.getScheduledTasks().size()+":"+
                        before.equals(retired.stream().map(j->j.calls.get()).toList()));
                }
            }
            """;
}
