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

class ConditionalStaticInitialiserReloadTest {
    @TempDir Path tmp;

    @Test
    void conditionalFieldsInitializeOnceWithoutReplayingOldStaticCode() throws Exception {
        try (var app = WatchedApp.in(tmp).jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("State", STATE).with("Holder", holder(0)).start()) {
            app.awaitOrFail("READY", "application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, "initial:77:1:0:0:0");
            reload(app, 1); probe(app, 1, "left:true:77:1:1:1:0");
            reload(app, 2); probe(app, 2, "user:true:right:77:1:2:1:1");
            reload(app, 3); probe(app, 3, "user:true:right:77:1:2:1:1");
        }
    }
    private void reload(WatchedApp app, int stage) throws Exception {
        app.rewrite("Holder", holder(stage));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
        assertEquals(stage, reloads(app), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Holder") || s.contains("Structural reload: app.Holder")).count();
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("CONDITIONAL" + stage + "=", "probe did not run");
        String observed = app.latest("CONDITIONAL" + stage + "=");
        System.out.println("[conditional-statics] " + observed);
        assertEquals("CONDITIONAL" + stage + "=" + expected, observed, app.tail());
    }
    private static String holder(int stage) {
        String fields = stage == 0 ? "" : """
                static String CHOICE = State.condition() ? State.left() : State.right();
                static String NULLABLE = State.flag ? null : State.right();
                """;
        if (stage >= 2) fields += "static String LATER = State.condition() ? State.left() : State.right();";
        String value = stage == 0 ? "\"initial\"" : "CHOICE + \":\" + (NULLABLE == null)";
        if (stage >= 2) value += " + \":\" + LATER";
        return """
                package app;
                public class Holder {
                    static int existing = 17;
                    static { State.blocks++; }
                    %s
                    public static int version() { return %d; }
                    public static void mutate() { %s }
                    public static String describe() { return %s + ":" + existing; }
                }
                """.formatted(fields, stage, stage == 0 ? "" : "CHOICE = \"user\";", value);
    }
    private static final String STATE = """
            package app;
            public class State {
                public static boolean flag = true;
                public static int conditions, leftCalls, rightCalls, blocks;
                public static boolean condition() { conditions++; return flag; }
                public static String left() { leftCalls++; return "left"; }
                public static String right() { rightCalls++; return "right"; }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    Holder.existing = 77;
                    Path dir = Path.of(System.getProperty("test.dir"));
                    System.out.println("READY");
                    for (int stage = 0; stage < 4; stage++) {
                        while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                        System.out.println("CONDITIONAL" + stage + "=" + Holder.describe() + ":" + State.blocks
                                + ":" + State.conditions + ":" + State.leftCalls + ":" + State.rightCalls);
                        if (stage == 1) { Holder.mutate(); State.flag = false; }
                    }
                }
            }
            """;
}
