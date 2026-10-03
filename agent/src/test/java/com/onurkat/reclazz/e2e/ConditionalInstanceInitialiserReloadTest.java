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

class ConditionalInstanceInitialiserReloadTest {
    @TempDir Path tmp;

    @Test
    void existingObjectsUseTheirStateWithoutRerunningConstructors() throws Exception {
        runScenario(false, false);
    }

    @Test
    void equivalentConstructorRoutesPreserveOldAndNewInstancesAcrossReloads() throws Exception {
        runScenario(true, false);
    }

    @Test
    void switchAndUnrelatedHandlersAcrossConstructorRoutes() throws Exception {
        runScenario(true, true);
    }

    private void runScenario(boolean multiple, boolean richer) throws Exception {
        String main = multiple ? APP.replace("two = new Holder(false), unread = new Holder(false)",
                "two = new Holder(3L, false), unread = new Holder()")
                .replace("if (stage == 1) { one.assign", "if (stage == 3) { Holder a = new Holder(true), b = new Holder(9L, false), c = new Holder(); "
                        + "System.out.println(\"NEW=\" + a.describe() + \"|\" + b.describe() + \"|\" + c.describe() + \":\" + Counts.constructors + \":\" + Counts.delegations + \":\" + a.calls + \",\" + b.calls + \",\" + c.calls + \":\" + a.enabled + \",\" + b.enabled + \",\" + c.enabled); } "
                        + "if (stage == 1) { one.assign") : APP;
        if (richer) main = main.replace("System.out.println(\"INSTANCE\"", "System.out.println(\"BLOCKS\" + stage + \"=\" + Counts.blocks); System.out.println(\"INSTANCE\"");
        try (var app = WatchedApp.in(tmp).jvmArgs("-Dtest.dir=" + tmp)
                .with("App", main).with("Counts", "package app; public class Counts { public static int constructors, delegations, blocks; }")
                .with("Holder", holder(0, multiple, richer)).start()) {
            app.awaitOrFail("READY", "application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            probe(app, 0, "initial|initial|unread|initial:4:0,0,0,0:77");
            reload(app, 1, multiple, richer); probe(app, 1, "on1/true|off1/false|unread|null:4:1,1,0,0:77");
            reload(app, 2, multiple, richer); probe(app, 2, "user/true|off1/false|on2/true|null:4:1,1,1,0:77");
            reload(app, 3, multiple, richer); probe(app, 3, "user/true|off1/false|on2/true|null:4:1,1,1,0:77");
            // On new objects Java runs field initializers before constructor-body parameter assignments.
            if (multiple) app.awaitOrFail("NEW=off3/false|off3/false|off3/false:7:2:1,1,1:true,false,false",
                    "all new constructor routes run the current initializer exactly once before the constructor body");
            if (richer) for (int stage = 0; stage < 4; stage++)
                assertEquals("BLOCKS" + stage + "=8", app.latest("BLOCKS" + stage + "="), app.tail());
        }
    }
    private void reload(WatchedApp app, int stage, boolean multiple, boolean richer) throws Exception {
        app.rewrite("Holder", holder(stage, multiple, richer));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && reloads(app) < stage) Thread.sleep(25);
        assertEquals(stage, reloads(app), app.tail());
    }
    private long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Holder") || s.contains("Structural reload: app.Holder")).count();
    }
    private void probe(WatchedApp app, int stage, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + stage));
        app.awaitOrFail("INSTANCE" + stage + "=", "probe did not run");
        String observed = app.latest("INSTANCE" + stage + "=");
        System.out.println("[conditional-instance] " + observed);
        assertEquals("INSTANCE" + stage + "=" + expected, observed, app.tail());
    }
    private static String holder(int version, boolean multiple, boolean richer) {
        String fields = version == 0 ? "" : "private String mode = choose() ? left() : right(); private String optional = enabled ? null : \"other\";";
        if (richer) {
            fields = fields.replace("choose() ? left() : right()", "switch (choose() ? 1 : 0) { case 0 -> right(); case 1 -> left(); case 2 -> \"unused\"; default -> \"default\"; }")
                    .replace("enabled ? null : \"other\"", "switch (enabled ? 1 : 0) { case 1 -> null; case 100 -> \"unused\"; default -> \"other\"; }");
            String block = "{ try { Counts.blocks++; } catch (RuntimeException e) { Counts.blocks++; } }";
            fields = block + fields + block;
        }
        return """
                package app;
                public class Holder {
                    public boolean enabled;
                    public int calls;
                    public int existing = 17;
                    %s
                    public Holder(boolean enabled) { this.enabled = enabled; Counts.constructors++; }
                    %s
                    private boolean choose() { calls++; return enabled; }
                    private String left() { return "on%d"; }
                    private String right() { return "off%d"; }
                    public void assign(String value) { %s }
                    public String readMode() { return %s; }
                    public String describe() { return %s; }
                }
                """.formatted(fields,
                multiple ? "public Holder(long ignored, boolean enabled) { this.enabled = enabled; Counts.constructors++; } public Holder() { this(1L, false); Counts.delegations++; }" : "",
                version, version, version == 0 ? "" : "mode = value;",
                version == 0 ? "\"initial\"" : "mode",
                version == 0 ? "\"initial\"" : "mode + \"/\" + (optional == null)");
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    Holder one = new Holder(true), two = new Holder(false), unread = new Holder(false), nulled = new Holder(true);
                    one.existing = 77;
                    Path dir = Path.of(System.getProperty("test.dir"));
                    System.out.println("READY");
                    for (int stage = 0; stage < 4; stage++) {
                        while (!Files.exists(dir.resolve("probe" + stage))) Thread.sleep(10);
                        if (stage == 1) nulled.assign(null);
                        System.out.println("INSTANCE" + stage + "=" + one.describe() + "|" + two.describe()
                                + "|" + (stage < 2 ? "unread" : unread.describe()) + "|" + nulled.readMode()
                                + ":" + Counts.constructors + ":" + one.calls + "," + two.calls + "," + unread.calls + "," + nulled.calls
                                + ":" + one.existing);
                        if (stage == 1) { one.assign("user"); one.enabled = false; two.enabled = true; unread.enabled = true; }
                    }
                }
            }
            """;
}
