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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CoreCrossClassFieldReloadTest {
    @TempDir Path dir;

    @Test
    void directFieldsShareValuesAcrossObjectsTypesSavesAndNullReceivers() throws Exception {
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir)
                .with("Holder", "package app; public class Holder { public int existing; public String own() { return \"before\"; } }")
                .with("Caller", """
                        package app;
                        public class Caller {
                            public static String read(Holder h) { return "before:" + h.existing + ":" + new java.awt.Point(9, 0).x; }
                            public static void write(Holder h) { }
                            public static int nullRead() { return 17; }
                            public static void nullWrite() { }
                        }
                        """)
                .with("App", driver()).start()) {
            app.awaitOrFail("READY=", "both held objects must exist before fields are added");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            String id = app.latest("READY=").substring(6);
            probe(app, 0, "before:11:9/before:22:9 owner=before/before", id);
            long before = reloads(app);
            app.rewriteAll(Map.of("Holder", holder(1, 41), "Caller", caller(1)));
            awaitReloads(app, before + 2);
            String first = values(41, 9000000001L, 2.5, "seed", 11);
            String second = values(41, 9000000001L, 2.5, "seed", 22);
            probe(app, 1, "r1:" + first + "/r1:" + second + " owner=v1:" + first + "/v1:" + second, id);
            String written = values(52, 9000000052L, 3.75, "null", 11);
            probe(app, 2, "r1:" + written + "/r1:" + second + " owner=v1:" + written + "/v1:" + second, id);
            before = reloads(app);
            app.rewrite("Holder", holder(2, 84));
            awaitReloads(app, before + 1);
            probe(app, 3, "r1:" + written + "/r1:" + second + " owner=v2:" + written + "/v2:" + second, id);
            before = reloads(app);
            app.rewrite("Caller", caller(2));
            awaitReloads(app, before + 1);
            probe(app, 4, "r2:" + written + "/r2:" + second + " owner=v2:" + written + "/v2:" + second, id);
            probe(app, 5, "read=NullPointerException:write=NullPointerException", id);
            assertEquals(1, app.output().stream().filter(s -> s.startsWith("READY=")).count());
        }
    }

    private static String values(int number, long wide, double fraction, String text, int existing) {
        return number + ":" + wide + ":" + fraction + ":" + text + ":" + existing + ":9";
    }
    private void probe(WatchedApp app, int stage, String expected, String id) throws Exception {
        Files.createFile(dir.resolve("probe" + stage));
        app.awaitOrFail("CHECK" + stage + "=", "probe must run");
        assertEquals("CHECK" + stage + "=" + expected + " id=" + id, app.latest("CHECK" + stage + "="), app.tail());
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Structural reload: app.Holder") || s.contains("Reloaded app.Holder")
                || s.contains("Structural reload: app.Caller") || s.contains("Reloaded app.Caller")).count();
    }
    private static void awaitReloads(WatchedApp app, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (reloads(app) < expected && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(expected, reloads(app), app.tail());
    }
    private static String holder(int version, int initial) {
        return """
                package app;
                public class Holder {
                    public int existing;
                    public int number = %d;
                    public long wide = 9000000001L;
                    public double fraction = 2.5;
                    public String text = "seed";
                    public String own() { return "v%d:" + number + ":" + wide + ":" + fraction + ":" + text + ":" + existing + ":9"; }
                }
                """.formatted(initial, version);
    }
    private static String caller(int version) {
        return """
                package app;
                public class Caller {
                    public static String read(Holder h) {
                        return "r%d:" + h.number + ":" + h.wide + ":" + h.fraction + ":" + h.text + ":" + h.existing + ":" + new java.awt.Point(9, 0).x;
                    }
                    public static void write(Holder h) { h.number = 52; h.wide = 9000000052L; h.fraction = 3.75; h.text = null; }
                    public static int nullRead() { Holder h = null; return h.number; }
                    public static void nullWrite() { Holder h = null; h.number = 1; }
                }
                """.formatted(version);
    }
    private static String driver() {
        return """
                package app;
                import java.nio.file.*;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Holder first = new Holder(); first.existing = 11;
                        Holder second = new Holder(); second.existing = 22;
                        String id = ProcessHandle.current().pid() + ":" + java.util.UUID.randomUUID();
                        System.out.println("READY=" + id);
                        for (int stage = 0; stage < 6; stage++) {
                            while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe" + stage))) Thread.sleep(10);
                            if (stage == 2) Caller.write(first);
                            String result;
                            if (stage == 5) {
                                String read = "none", write = "none";
                                try { read = Integer.toString(Caller.nullRead()); } catch (Throwable e) { read = e.getClass().getSimpleName(); }
                                try { Caller.nullWrite(); } catch (Throwable e) { write = e.getClass().getSimpleName(); }
                                result = "read=" + read + ":write=" + write;
                            } else result = Caller.read(first) + "/" + Caller.read(second) + " owner=" + first.own() + "/" + second.own();
                            System.out.println("CHECK" + stage + "=" + result + " id=" + id);
                        }
                    }
                }
                """;
    }
}
