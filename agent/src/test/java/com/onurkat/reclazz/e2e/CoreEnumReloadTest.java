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

class CoreEnumReloadTest {
    @TempDir Path dir;

    @Test
    void appendTailAndReorderKeepOldCollectionsAndExposeOldArrayLimit() throws Exception {
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir)
                .with("Color", color("RED, BLUE", 1))
                .with("Labels", """
                        package app;
                        public class Labels {
                            public static String label(Color color) {
                                switch (color) { case RED: return "red"; case BLUE: return "blue"; default: return "other"; }
                            }
                        }
                        """).with("App", driver()).start()) {
            app.awaitOrFail("READY=", "old collections and switch must exist before append");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            String id = app.latest("READY=").substring(6);
            probe(app, 0, "[RED, BLUE]:ord=0,1:blue=old:green=absent:arrayLength=2:marker=v1", id);
            reload(app, color("RED, BLUE, GREEN", 2));
            probe(app, 1, appended(2), id);
            reload(app, color("RED, BLUE, GREEN", 3));
            probe(app, 2, appended(3), id);
            reload(app, color("RED, BLUE", 4));
            probe(app, 3, "[RED, BLUE]:ord=0,1:blue=old:green=absent:arrayLength=2:marker=v4", id);
            reload(app, color("RED, BLUE, GREEN", 5));
            probe(app, 4, appended(5), id);
            reload(app, color("BLUE, RED, GREEN", 6));
            probe(app, 5, appended(6), id);
            assertTrue(app.output().stream().anyMatch(s -> s.contains("reordered") && s.toLowerCase(java.util.Locale.ROOT).contains("restart")), app.tail());
            assertEquals(1, app.output().stream().filter(s -> s.startsWith("READY=")).count());
        }
    }

    private static String appended(int version) {
        return "[RED, BLUE, GREEN]:ord=0,1:blue=old:green=2/new/true/other/ArrayIndexOutOfBoundsException:arrayLength=2:marker=v" + version;
    }
    private void probe(WatchedApp app, int stage, String expected, String id) throws Exception {
        Files.createFile(dir.resolve("probe" + stage));
        app.awaitOrFail("CHECK" + stage + "=", "probe must execute");
        String line = "CHECK" + stage + "=" + expected + " id=" + id;
        assertEquals(line, app.latest("CHECK" + stage + "="), app.tail());
        System.out.println("[core-enums] " + line);
    }
    private void reload(WatchedApp app, String source) throws Exception {
        long before = reloads(app); app.rewrite("Color", source);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (reloads(app) <= before && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(before + 1, reloads(app), app.tail());
    }
    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Structural reload: app.Color") || s.contains("Reloaded app.Color")).count();
    }
    private static String color(String constants, int version) {
        return "package app; public enum Color { " + constants + "; public String marker() { return \"v" + version + "\"; } }";
    }
    private static String driver() {
        return """
                package app;
                import java.nio.file.*;
                import java.util.*;
                public class App {
                    public static void main(String[] args) throws Exception {
                        EnumMap<Color,String> oldMap = new EnumMap<>(Color.class); oldMap.put(Color.BLUE, "old");
                        EnumSet<Color> oldSet = EnumSet.of(Color.BLUE);
                        Color[] array = Color.values();
                        if (!Labels.label(Color.RED).equals("red") || !Labels.label(Color.BLUE).equals("blue")) throw new AssertionError();
                        String id = ProcessHandle.current().pid() + ":" + UUID.randomUUID();
                        System.out.println("READY=" + id);
                        for (int stage = 0; stage < 6; stage++) {
                            while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe" + stage))) Thread.sleep(10);
                            String green;
                            try {
                                Color extra = Color.valueOf("GREEN");
                                oldMap.put(extra, "new"); oldSet.add(extra);
                                String index;
                                try { index = array[extra.ordinal()].name(); }
                                catch (ArrayIndexOutOfBoundsException e) { index = e.getClass().getSimpleName(); }
                                green = extra.ordinal() + "/" + oldMap.get(extra) + "/" + oldSet.contains(extra) + "/" + Labels.label(extra) + "/" + index;
                            } catch (IllegalArgumentException absent) { green = "absent"; }
                            System.out.println("CHECK" + stage + "=" + Arrays.toString(Color.values())
                                + ":ord=" + Color.RED.ordinal() + "," + Color.BLUE.ordinal() + ":blue=" + oldMap.get(Color.BLUE)
                                + ":green=" + green + ":arrayLength=" + array.length + ":marker=" + Color.RED.marker() + " id=" + id);
                        }
                    }
                }
                """;
    }
}
