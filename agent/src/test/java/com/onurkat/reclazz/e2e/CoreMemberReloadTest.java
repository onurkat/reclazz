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

class CoreMemberReloadTest {
    @TempDir Path dir;

    @Test
    void directAddedFieldCallerSharesOwnerValuesAcrossSaves() throws Exception {
        try (var app = WatchedApp.in(dir)
                .with("Holder", "package app; public class Holder { public int value() { return 17; } }")
                .with("Caller", "package app; public class Caller { public static String call(Holder h) { return Integer.toString(h.value()); } }")
                .with("App", """
                        package app;
                        public class App {
                            public static void main(String[] args) throws Exception {
                                Holder held = new Holder();
                                String id = ProcessHandle.current().pid() + ":" + java.util.UUID.randomUUID();
                                System.out.println("ID=" + id);
                                while (true) {
                                    String access;
                                    try { access = Caller.call(held); }
                                    catch (Throwable e) { access = e.getClass().getSimpleName(); }
                                    System.out.println("LIVE=access=" + access + ":own=" + held.value() + " id=" + id);
                                    Thread.sleep(100);
                                }
                            }
                        }
                        """).start()) {
            app.awaitOrFail("LIVE=access=17:own=17", "ordinary cross-class caller control");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            String id = app.latest("ID=").substring(3);
            app.rewriteAll(Map.of("Holder", "package app; public class Holder { public int added = 42; public int value() { return added; } }",
                    "Caller", "package app; public class Caller { public static String call(Holder h) { return Integer.toString(h.added); } }"));
            app.awaitOrFail("LIVE=access=42:own=42 id=" + id,
                    "direct access must share the owning class first-read value");
            app.rewrite("Caller", "package app; public class Caller { public static String call(Holder h) { h.added = 91; return Integer.toString(h.added); } }");
            app.awaitOrFail("LIVE=access=91:own=91 id=" + id, "external writes must reach the same owner store");
            app.rewrite("Holder", "package app; public class Holder { public int added = 73; public int value() { return added; } }");
            app.awaitOrFail("LIVE=access=91:own=91 id=" + id, "a later owner save must preserve the written value");
            app.rewrite("Caller", "package app; public class Caller { public static String call(Holder h) { return Integer.toString(h.value()); } }");
            app.awaitOrFail("LIVE=access=91:own=91 id=" + id, "owner accessor must agree after direct read/write");
            assertEquals(1, app.output().stream().filter(s -> s.startsWith("ID=")).count());
        }
    }

    @Test
    void addedCallsAndRemovedReflectionKeepHeldMembersAcrossRestore() throws Exception {
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir)
                .with("Holder", holder(1, true, false))
                .with("Caller", "package app; public class Caller { public static String call(Holder h) { return \"none\"; } }")
                .with("Legacy", "package app; public class Legacy { public static String call(Holder h) { return h.gone() + \":\" + h.legacy; } }")
                .with("App", driver()).start()) {
            app.awaitOrFail("READY=", "original held members must be captured");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            String id = app.latest("READY=").substring(6);
            probe(app, 0, "v1:none:gone1:23:gone1:23:visible=true,true:addedVisible=false:false", id);

            long before = reloads(app);
            app.rewriteAll(Map.of("Holder", holder(2, true, true), "Caller",
                    "package app; public class Caller { public static String call(Holder h) { return h.extra() + \":\" + h.readAdded(); } }"));
            awaitReloads(app, before + 2);
            probe(app, 1, "v2:extra2:7:gone2:23:gone2:23:visible=true,true:addedVisible=false:false", id);

            reload(app, holder(3, false, true));
            probe(app, 2, "v3:extra3:7:gone2:23:gone2:23:visible=false,false:addedVisible=false:false", id);
            reload(app, holder(4, true, true));
            probe(app, 3, "v4:extra4:7:gone4:23:gone4:23:visible=true,true:addedVisible=false:false", id);
            reload(app, holder(5, false, true));
            probe(app, 4, "v5:extra5:7:gone4:23:gone4:23:visible=false,false:addedVisible=false:false", id);
            assertEquals(1, app.output().stream().filter(s -> s.startsWith("READY=")).count());
        }
    }

    private void probe(WatchedApp app, int stage, String expected, String id) throws Exception {
        Files.createFile(dir.resolve("probe" + stage));
        String line = "CHECK" + stage + "=" + expected + " id=" + id;
        app.awaitOrFail("CHECK" + stage + "=", "probe must execute");
        assertEquals(line, app.latest("CHECK" + stage + "="), app.tail());
        System.out.println("[core-members] " + line);
    }

    private void reload(WatchedApp app, String source) throws Exception {
        long before = reloads(app); app.rewrite("Holder", source); awaitReloads(app, before + 1);
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
    private static String holder(int version, boolean legacy, boolean extra) {
        return "package app; public class Holder { public String version() { return \"v" + version + "\"; }"
                + (legacy ? " public int legacy = 17; public String gone() { return \"gone" + version + "\"; }" : "")
                + (extra ? " public int added = 7; public int readAdded() { return added; } public String extra() { return \"extra" + version + "\"; }" : "") + " }";
    }
    private static String driver() {
        return """
                package app;
                import java.lang.reflect.*;
                import java.nio.file.*;
                import java.util.Arrays;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Holder held = new Holder(); held.legacy = 23;
                        Method method = Holder.class.getDeclaredMethod("gone");
                        Field field = Holder.class.getDeclaredField("legacy");
                        String id = ProcessHandle.current().pid() + ":" + java.util.UUID.randomUUID();
                        System.out.println("READY=" + id);
                        for (int stage = 0; stage < 5; stage++) {
                            while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe" + stage))) Thread.sleep(10);
                            // Meta-reflection reaches the original Class through the JDK, without a rewritten member lookup.
                            Method[] methods = (Method[]) Class.class.getMethod("getDeclaredMethods").invoke(Holder.class);
                            Field[] fields = (Field[]) Class.class.getMethod("getDeclaredFields").invoke(Holder.class);
                            boolean gone = Arrays.stream(methods).anyMatch(m -> m.getName().equals("gone"));
                            boolean legacy = Arrays.stream(fields).anyMatch(f -> f.getName().equals("legacy"));
                            boolean extra = Arrays.stream(methods).anyMatch(m -> m.getName().equals("extra"));
                            boolean added = Arrays.stream(fields).anyMatch(f -> f.getName().equals("added"));
                            System.out.println("CHECK" + stage + "=" + held.version() + ":" + Caller.call(held)
                                + ":" + Legacy.call(held) + ":" + method.invoke(held) + ":" + field.get(held)
                                + ":visible=" + gone + "," + legacy + ":addedVisible=" + extra + ":" + added + " id=" + id);
                        }
                    }
                }
                """;
    }
}
