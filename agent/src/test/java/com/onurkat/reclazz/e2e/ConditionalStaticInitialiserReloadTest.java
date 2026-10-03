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
        runScenario(false);
    }
    @Test
    void switchAndUnrelatedHandlersDoNotReplayStaticBlocks() throws Exception {
        runScenario(true);
    }
    private void runScenario(boolean richer) throws Exception {
        String blocks = richer ? "2" : "1";
        try (var app = WatchedApp.in(tmp).jvmArgs("-Dtest.dir=" + tmp)
                .with("App", APP).with("State", STATE).with("Holder", holder(0, richer)).start()) {
            app.awaitOrFail("READY", "application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, "initial:77:" + blocks + ":0:0:0");
            reload(app, 1, richer); probe(app, 1, "left:true:77:" + blocks + ":1:1:0");
            reload(app, 2, richer); probe(app, 2, "user:true:right:77:" + blocks + ":2:1:1");
            reload(app, 3, richer); probe(app, 3, "user:true:right:77:" + blocks + ":2:1:1");
        }
    }
    private void reload(WatchedApp app, int stage, boolean richer) throws Exception {
        app.rewrite("Holder", holder(stage, richer));
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
    private static String holder(int stage, boolean richer) {
        String fields = stage == 0 ? "" : """
                static String CHOICE = State.condition() ? State.left() : State.right();
                static String NULLABLE = State.flag ? null : State.right();
                """;
        if (stage >= 2) fields += "static String LATER = State.condition() ? State.left() : State.right();";
        String block = "static { State.blocks++; }";
        if (richer) {
            fields = fields.replace("State.condition() ? State.left() : State.right()", "switch (State.condition() ? 1 : 0) { case 0 -> State.right(); case 1 -> State.left(); case 2 -> \"unused\"; default -> \"default\"; }")
                    .replace("State.flag ? null : State.right()", "switch (State.flag ? 1 : 0) { case 1 -> null; case 100 -> \"unused\"; default -> State.right(); }");
            block = "static { try { State.blocks++; } catch (RuntimeException e) { State.blocks++; } }";
            fields += block;
        }
        String value = stage == 0 ? "\"initial\"" : "CHOICE + \":\" + (NULLABLE == null)";
        if (stage >= 2) value += " + \":\" + LATER";
        return """
                package app;
                public class Holder {
                    static int existing = 17;
                    %s
                    %s
                    public static int version() { return %d; }
                    public static void mutate() { %s }
                    public static String describe() { return %s + ":" + existing; }
                }
                """.formatted(block, fields, stage, stage == 0 ? "" : "CHOICE = \"user\";", value);
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
