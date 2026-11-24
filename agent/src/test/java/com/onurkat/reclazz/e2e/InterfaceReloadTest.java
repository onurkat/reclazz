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

/** R33: what a save of an interface does on a stock JVM. */
class InterfaceReloadTest {
    @TempDir Path dir;

    private static String greeter(String body) {
        return "package app; public interface Greeter { String name(); " + body + " }";
    }
    private static final String IMPL = "package app; public class Impl implements Greeter { public String name() { return \"impl\"; } }";
    private static final String APP = """
            package app;
            import java.nio.file.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    Greeter g = new Impl();
                    System.out.println("READY=" + g.greet());
                    for (int stage = 0; stage < 5; stage++) {
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe" + stage))) Thread.sleep(10);
                        String extra;
                        try { extra = String.valueOf(Greeter.class.getMethod("shout").invoke(g)); }
                        catch (NoSuchMethodException absent) { extra = "absent"; }
                        catch (Throwable t) { extra = t.getClass().getSimpleName(); }
                        System.out.println("CHECK" + stage + "=" + g.greet() + ":" + extra);
                    }
                }
            }
            """;

    @Test
    void defaultMethodBodyReloadsCleanlyAndAnAddedMethodNamesTheInterfaceReason() throws Exception {
        try (var app = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir)
                .with("Greeter", greeter("default String greet() { return \"v1:\" + name(); }"))
                .with("Impl", IMPL).with("App", APP).start()) {
            app.awaitOrFail("READY=", "app started");
            app.awaitOrFail("] Watching 1 director", "watcher ready");
            assertEquals("READY=v1:impl", app.latest("READY="));

            app.rewrite("Greeter", greeter("default String greet() { return \"v2:\" + name(); }"));
            app.awaitOrFail("Reloaded app.Greeter", "a default-method body edit must reload");
            Files.createFile(dir.resolve("probe0"));
            app.awaitOrFail("CHECK0=", "probe0");
            assertEquals("CHECK0=v2:impl:absent", app.latest("CHECK0="), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Greeter was loaded before Reclazz could instrument it")), app.tail());
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Reloaded app.Greeter") && s.contains("restart")), app.tail());

            app.rewrite("Greeter", greeter("default String greet() { return \"v2:\" + name(); } default String shout() { return greet().toUpperCase(); }"));
            app.awaitOrFail("an interface method or field was added", "an added interface method must be refused with the interface reason");
            Files.createFile(dir.resolve("probe1"));
            app.awaitOrFail("CHECK1=", "probe1");
            assertEquals("CHECK1=v2:impl:absent", app.latest("CHECK1="), "the refused save leaves the running interface as it was");
            assertFalse(app.output().stream().anyMatch(s -> s.contains("Greeter was loaded before Reclazz could instrument it")), app.tail());

            app.rewrite("Greeter", greeter("default String greet() { return \"v3:\" + name(); }"));
            long reloads = app.output().stream().filter(s -> s.contains("Reloaded app.Greeter")).count();
            for (int i = 0; i < 300 && app.output().stream().filter(s -> s.contains("Reloaded app.Greeter")).count() == reloads; i++) Thread.sleep(50);
            Files.createFile(dir.resolve("probe2"));
            app.awaitOrFail("CHECK2=", "probe2");
            assertEquals("CHECK2=v3:impl:absent", app.latest("CHECK2="), "a later body edit still reloads: " + app.tail());
        }
    }

    /** R33 audit F1: a changed interface static initializer is not applied, so it must leave a restart, with the option off or on. */
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"resetStatics=false", "resetStatics=true", "excludeClasses=app.Holder"})
    void changedInterfaceInitializerReportsARestart(String option) throws Exception {
        String app = """
                package app;
                import java.nio.file.*;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Holder h = new Holder() { };
                        System.out.println("READY=" + h.value() + ":" + Holder.stamp());
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe0"))) Thread.sleep(10);
                        System.out.println("CHECK0=" + h.value() + ":" + Holder.stamp());
                        while (!Files.exists(Path.of(System.getProperty("probe.dir"), "probe1"))) Thread.sleep(10);
                    }
                }
                """;
        java.util.function.BiFunction<String,String,String> holder = (value, stamp) -> "package app; import java.util.List; public interface Holder { "
                + "Object VALUE = List.of(\"" + value + "\"); default Object value() { return VALUE; } static String stamp() { return \"" + stamp + "\"; } }";
        try (var running = WatchedApp.in(dir).jvmArgs("-Dprobe.dir=" + dir).agentArgs(option)
                .with("Holder", holder.apply("old", "v1")).with("App", app).start()) {
            running.awaitOrFail("READY=", "app started");
            running.awaitOrFail("] Watching 1 director", "watcher ready");
            running.rewrite("Holder", holder.apply("new", "v2"));
            running.awaitOrFail("static initialization of interface changed", "the unapplied initializer must be named");
            Files.createFile(dir.resolve("probe0"));
            running.awaitOrFail("CHECK0=", "probe0");
            assertEquals("CHECK0=[old]:v2", running.latest("CHECK0="), running.tail());
            // The summary line follows the warnings; the probe can answer before it is printed (Windows gate ee78e927).
            running.awaitOrFail("Reloaded app.Holder", "the reload summary");
            Files.createFile(dir.resolve("probe1"));
            assertTrue(running.output().stream().noneMatch(s -> s.contains("not-prepared")), "interface fields are never prepared: " + running.tail());
            assertTrue(running.output().stream().anyMatch(s -> s.contains("Reloaded app.Holder") && s.toLowerCase(java.util.Locale.ROOT).contains("restart")), running.tail());
        }
    }
}
