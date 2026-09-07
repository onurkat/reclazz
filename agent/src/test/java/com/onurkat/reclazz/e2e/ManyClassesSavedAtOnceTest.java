/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An IDE build writes every changed class file at once. Each of those beans
 * is refreshed, and every bean holding one of them has to be re-pointed at
 * the new instance, which is a walk over every singleton in the context.
 * Done per class, a save of thirty beans in a context of two thousand walks
 * the two thousand thirty times; done per batch, once. What is asserted is
 * that every one of the thirty serves its new version afterwards, that the
 * save arrived as one batch, and that the walk happened at most once.
 */
class ManyClassesSavedAtOnceTest {

    /** Sized to run in a few seconds; -Preclazz.bench.beans and -Preclazz.bench.saved scale it up. */
    private static final int BEANS = Integer.getInteger("reclazz.bench.beans", 2000);
    private static final int SAVED = Integer.getInteger("reclazz.bench.saved", 30);

    @TempDir
    Path tmp;

    @Test
    void thirtyBeansSavedTogetherAreRefreshedTogether() throws Exception {
        WatchedApp.Builder builder = WatchedApp.in(tmp)
                .classpath(springOnlyClasspath())
                .agentArgs("startupDelaySec=1,debounceMs=200"
                        + (Boolean.getBoolean("reclazz.bench.trace") ? ",verbose=true" : ""))
                .with("App", driver())
                .with("Hub", hub());
        for (int i = 0; i < BEANS; i++) {
            builder.with("Bean" + i, bean(i, "v1"));
        }
        try (WatchedApp app = builder.start()) {
            app.awaitOrFail("APP_STARTED", "the app did not start under the agent");
            app.awaitOrFail("VERSIONS=" + "v1".repeat(SAVED), "the first version never served");

            Map<String, String> edits = new LinkedHashMap<>();
            for (int i = 0; i < SAVED; i++) {
                edits.put("Bean" + i, bean(i, "v2"));
            }
            long saved = System.currentTimeMillis();
            app.rewriteAll(edits);

            assertTrue(app.awaits("VERSIONS=" + "v2".repeat(SAVED), 120),
                    () -> "not every saved bean serves its new version:\n" + app.tail());
            long served = System.currentTimeMillis() - saved;

            long reloadedLines = app.output().stream().filter(l -> l.contains("Reloaded app.Bean")).count();
            long sweeps = app.output().stream().filter(l -> l.contains("Re-pointed")).count();
            // From the agent noticing the first class file to the application
            // serving every new version: the part of the wait the agent owns.
            // What comes before it is javac and the file watcher's poll.
            long noticed = app.firstSeenMillis("Class file changed: Bean");
            long allServed = app.lastSeenMillis("VERSIONS=" + "v2".repeat(SAVED));
            System.out.println("[diag] beans=" + BEANS + " saved=" + SAVED
                    + " savedToServedMs=" + served
                    + " noticedToServedMs=" + (allServed - noticed)
                    + " reloadedLines=" + reloadedLines
                    + " singletonSweeps=" + sweeps);
            if (Boolean.getBoolean("reclazz.bench.trace")) {
                app.output().stream().filter(l -> l.contains("[Reclazz]"))
                        .forEach(l -> System.out.println("[trace] " + l));
            }

            assertEquals(SAVED, reloadedLines, "each saved class reloads exactly once");
            assertTrue(app.output().stream().anyMatch(l -> l.contains(SAVED + " class files changed together")),
                    () -> "the whole save should arrive as one batch, not a batch and stragglers:\n"
                            + app.output().stream().filter(l -> l.contains("changed together"))
                                    .collect(Collectors.joining("\n")));
            // Once, or not at all: when the whole save is one batch the hub that
            // holds every saved bean is itself refreshed by the cascade, so there
            // may be nothing stale left for the sweep to re-point.
            assertTrue(sweeps <= 1,
                    () -> "the singleton sweep should run at most once for the whole save, ran " + sweeps + ":\n"
                            + app.output().stream().filter(l -> l.contains("Re-pointed"))
                                    .collect(Collectors.joining("\n")));
        }
    }

    private static String bean(int i, String version) {
        return """
                package app;
                import org.springframework.stereotype.Service;
                @Service
                public class Bean%d {
                    public String v() { return "%s"; }
                }
                """.formatted(i, version);
    }

    /** Holds the saved beans by field, so each refresh leaves it a stale reference to heal. */
    private static String hub() {
        String fields = IntStream.range(0, SAVED)
                .mapToObj(i -> "    @Autowired public Bean%d b%d;".formatted(i, i))
                .collect(Collectors.joining("\n"));
        String concat = IntStream.range(0, SAVED)
                .mapToObj(i -> "b%d.v()".formatted(i))
                .collect(Collectors.joining(" + "));
        return """
                package app;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;
                @Service
                public class Hub {
                %s
                    public String versions() { return %s; }
                }
                """.formatted(fields, concat);
    }

    private static String driver() {
        return """
                package app;
                import org.springframework.context.annotation.AnnotationConfigApplicationContext;
                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                @ComponentScan("app")
                public class App {
                    public static void main(String[] args) throws Exception {
                        var ctx = new AnnotationConfigApplicationContext(App.class);
                        System.out.println("APP_STARTED");
                        while (true) {
                            Thread.sleep(250);
                            System.out.println("VERSIONS=" + ctx.getBean(Hub.class).versions());
                        }
                    }
                }
                """;
    }

    private static String springOnlyClasspath() {
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> new File(p).getName().startsWith("spring-"))
                .collect(Collectors.joining(File.pathSeparator));
    }
}
