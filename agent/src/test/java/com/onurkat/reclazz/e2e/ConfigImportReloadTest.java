/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R38 slice A: {@code spring.config.import} entries added, reordered and removed in a running Boot application. The
 * correctness bar is a restart: a second JVM started on the same files must report the same ordered Boot sources and the
 * same values.
 */
class ConfigImportReloadTest {
    @TempDir Path tmp;
    @TempDir Path fresh;

    @Test void importsAreAddedReorderedRemovedAndMatchARestart() throws Exception {
        Path conf = Files.createDirectories(tmp.resolve("classes"));
        Path main = conf.resolve("application.properties"), a = conf.resolve("a.properties"), b = conf.resolve("b.yml");
        Path nested = conf.resolve("nested.properties");
        Files.writeString(main, "svc.note=base\nsvc.timeout=1\n");
        Files.writeString(a, "svc.note=a\n");
        Files.writeString(b, "svc:\n  note: b0\n  timeout: 2\n---\nsvc:\n  extra: b1\n");
        Files.writeString(nested, "svc.note=nested\nspring.config.import=optional:file:" + u(a) + "\n");
        String snake = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("snakeyaml-")).findFirst().orElseThrow();
        String classpath = WatchedApp.springClasspath() + File.pathSeparator + snake;
        try (var app = WatchedApp.in(tmp).classpath(classpath).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dconfig.file=" + u(main), "-Dprobe.dir=" + tmp).with("App", APP).with("Settings", SETTINGS).start()) {
            app.awaitOrFail("READY", "Boot application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            assertTrue(probe(app, 0).startsWith("base:1:none:base|"), app.tail());

            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=optional:file:" + u(a) + ",file:" + u(b) + "\n");
            assertNotNull(await(app, 1, "b0:2:b1:b0|"), "imports added: " + app.tail());

            Files.writeString(a, "svc.note=a2\n");
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=file:" + u(b) + ",optional:file:" + u(a) + "\n");
            String live = await(app, 2, "a2:2:b1:a2|");
            assertNotNull(live, "an edited imported file and a reordered list: " + app.tail());

            try (var restarted = WatchedApp.in(fresh).classpath(classpath).agentArgs("startupDelaySec=1")
                    .jvmArgs("-Dconfig.file=" + u(main), "-Donce=true").with("App", APP).with("Settings", SETTINGS).start()) {
                restarted.awaitOrFail("ONCE=", "restarted application did not report");
                assertEquals(restarted.latest("ONCE=").substring(5), live, "a restart on the same files builds the same sources and values");
            }

            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=file:" + u(nested) + "\n");
            String nestedLive = await(app, 5, "a2:1:none:a2|");
            assertNotNull(nestedLive, "a nested import applies, the nested file above its importer: " + app.tail());
            assertRestartEqual(classpath, main, nestedLive);
            Files.writeString(nested, "svc.note=nested\nspring.profiles.active=dev\n");
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=file:" + u(nested) + ",optional:file:" + u(a) + "\n");
            app.awaitOrFail("profile changes in imported files require a restart", "a profile change inside an imported file was not held");
            Files.writeString(nested, "svc.note=nested\nspring.config.import=optional:file:" + u(a) + "\n");
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=file:" + u(conf.resolve("missing.properties")) + "\n");
            app.awaitOrFail("names a location that does not exist", "a missing required import was not held");
            assertNotNull(await(app, 3, "a2:1:none:a2|"), "held saves changed nothing: " + app.tail());

            Files.writeString(main, "svc.note=base\nsvc.timeout=1\n");
            String removed = await(app, 4, "base:1:none:base|");
            assertNotNull(removed, "imports removed: " + app.tail());
            assertFalse(removed.contains("a.properties"), removed);
            System.out.println("[r38-imports] add/edit/reorder/remove/nested applied; restart-equal sources and values; profile-in-import and missing held 2/2");
        }
    }

    /** Every import shape Boot accepts from a file system, in a multi-document YAML importer, each compared with a restart. */
    @Test void everyImportShapeMatchesARestart() throws Exception {
        Path conf = Files.createDirectories(tmp.resolve("classes"));
        Path main = conf.resolve("application.yml");
        Files.createDirectories(conf.resolve("sub")); Files.createDirectories(conf.resolve("D")); Files.createDirectories(conf.resolve("W/x"));
        Files.createDirectories(conf.resolve("W/y")); Files.createDirectories(conf.resolve("T/svc"));
        Files.writeString(conf.resolve("sub/r.properties"), "svc.note=r\nspring.config.import=rr.properties\n");
        Files.writeString(conf.resolve("sub/rr.properties"), "svc.extra=rr\n");
        Files.writeString(conf.resolve("D/application.properties"), "svc.note=d\n");
        Files.writeString(conf.resolve("D/application.yml"), "svc:\n  extra: dy\n  timeout: 4\n");
        Files.writeString(conf.resolve("W/x/application.properties"), "svc.note=wx\n");
        Files.writeString(conf.resolve("W/y/application.properties"), "svc.extra=wy\n");
        Files.writeString(conf.resolve("T/svc/note"), "tree");
        Files.writeString(main, "svc:\n  note: base\n  timeout: 1\n---\nsvc:\n  extra: second\n");
        String snake = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("snakeyaml-")).findFirst().orElseThrow();
        String classpath = WatchedApp.springClasspath() + File.pathSeparator + snake;
        String[][] steps = {
                // unprefixed and relative to the importer, with its own nested unprefixed import
                {"svc:\n  note: base\n  timeout: 1\nspring:\n  config:\n    import: sub/r.properties\n---\nsvc:\n  extra: second\n", "r:1:second:r|"},
                // a directory and a wildcard, imported from two different documents
                {"svc:\n  note: base\n  timeout: 1\nspring:\n  config:\n    import: file:" + u(conf) + "/D/\n---\nsvc:\n  extra: second\nspring:\n  config:\n    import: file:" + u(conf) + "/W/*/\n", "wx:4:wy:wx|"},
                // a config tree
                {"svc:\n  note: base\n  timeout: 1\nspring:\n  config:\n    import: configtree:" + u(conf) + "/T/\n---\nsvc:\n  extra: second\n", "tree:1:second:tree|"},
                // everything removed again
                {"svc:\n  note: base\n  timeout: 1\n---\nsvc:\n  extra: second\n", "base:1:second:base|"},
        };
        try (var app = WatchedApp.in(tmp).classpath(classpath).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dconfig.file=" + u(main), "-Dprobe.dir=" + tmp).with("App", APP).with("Settings", SETTINGS).start()) {
            app.awaitOrFail("READY", "Boot application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            for (int i = 0; i < steps.length; i++) {
                Files.writeString(main, steps[i][0]);
                String live = await(app, 10 + i, steps[i][1]);
                assertNotNull(live, "step " + i + " (" + steps[i][1] + "): " + agentLines(app));
                assertRestartEqual(classpath, main, live);
            }
            System.out.println("[r38-imports] relative+nested, directory, wildcard, configtree, multi-document importer, removal: " + steps.length + "/" + steps.length + " restart-equal");
        }
    }

    /**
     * The common layout: application.properties on the classpath (no spring.config.location), an import present at
     * startup, nested imports added and removed in either order, a config tree value with a trailing new line, and an
     * import shared by two files.
     */
    /**
     * Boot resolves an unprefixed import against the importer's location text up to its last '/'. A Windows location
     * written with backslashes has none, so Boot looks in the working directory and a restart fails; the agent must not
     * apply the import from the file's own directory instead.
     */
    @Test void aBackslashLocationResolvesImportsAgainstTheWorkingDirectoryLikeBoot() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(File.separatorChar == '\\', "Windows paths only");
        Path conf = Files.createDirectories(tmp.resolve("classes"));
        Path main = conf.resolve("application.properties");
        Files.createDirectories(conf.resolve("sub"));
        Files.writeString(conf.resolve("sub/r.properties"), "svc.note=r\n");
        Files.writeString(main, "svc.note=base\nsvc.timeout=1\n");
        String snake = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("snakeyaml-")).findFirst().orElseThrow();
        String classpath = WatchedApp.springClasspath() + File.pathSeparator + snake;
        assertTrue(main.toString().indexOf('/') < 0, main.toString());
        try (var app = WatchedApp.in(tmp).classpath(classpath).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dconfig.file=" + main, "-Dprobe.dir=" + tmp).with("App", APP).with("Settings", SETTINGS).start()) {
            app.awaitOrFail("READY", "Boot application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            assertTrue(probe(app, 0).startsWith("base:1:none:base|"), app.tail());
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=sub/r.properties\n");
            assertTrue(app.awaits("a restart would fail too", 30), agentLines(app));
            assertTrue(probe(app, 1).startsWith("base:1:none:base|"), app.tail());
        }
        try (var restarted = WatchedApp.in(fresh).classpath(classpath).agentArgs("startupDelaySec=1")
                .jvmArgs("-Dconfig.file=" + main, "-Donce=true").with("App", APP).with("Settings", SETTINGS).start()) {
            assertTrue(restarted.awaits("does not exist", 60), "a restart fails on the same files: " + restarted.tail());
        }
    }

    @Test void classpathImportsPresentAtStartupMatchARestart() throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path sub = Files.createDirectories(classes.resolve("sub")), tree = Files.createDirectories(classes.resolve("T/svc"));
        Path main = classes.resolve("application.properties"), c = sub.resolve("c.properties");
        Files.writeString(c, "svc.note=c\nspring.config.import=cc.properties\n");
        Files.writeString(sub.resolve("cc.properties"), "svc.extra=cc\n");
        Files.writeString(sub.resolve("d.properties"), "svc.timeout=4\n");
        Files.writeString(sub.resolve("e.properties"), "svc.note=e\nspring.config.import=shared.properties\n");
        Files.writeString(sub.resolve("shared.properties"), "svc.extra=shared\n");
        Files.writeString(tree.resolve("note"), "tree\n");
        Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=sub/c.properties\n");
        String snake = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("snakeyaml-")).findFirst().orElseThrow();
        String classpath = WatchedApp.springClasspath() + File.pathSeparator + snake;
        try (var app = WatchedApp.in(tmp).classpath(classpath).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Settings", SETTINGS).start()) {
            app.awaitOrFail("READY", "Boot application did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            assertTrue(probe(app, 30).startsWith("c:1:cc:c|"), app.tail());
            // An import present at startup, removed: it and its nested import go.
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\n");
            String removed = await(app, 31, "base:1:none:base|");
            assertNotNull(removed, agentLines(app));
            assertRestartEqual(classpath, null, removed);
            // Added back, then the imported file gains its own import, then the outer import goes: both go.
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=sub/c.properties\n");
            assertNotNull(await(app, 32, "c:1:cc:c|"), agentLines(app));
            Files.writeString(c, "svc.note=c\nspring.config.import=cc.properties,d.properties\n");
            String nested = await(app, 33, "c:4:cc:c|");
            assertNotNull(nested, agentLines(app));
            assertRestartEqual(classpath, null, nested);
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\n");
            String outerGone = await(app, 34, "base:1:none:base|");
            assertNotNull(outerGone, agentLines(app));
            assertRestartEqual(classpath, null, outerGone);
            // A config tree value ends with a new line; Boot trims it.
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=configtree:" + u(classes.resolve("T")) + "/\n");
            String treeLive = await(app, 35, "tree:1:none:tree|");
            assertNotNull(treeLive, agentLines(app));
            assertRestartEqual(classpath, null, treeLive);
            // One file reached twice (through e.properties and directly): Boot loads it once, at its first import.
            Files.writeString(main, "svc.note=base\nsvc.timeout=1\nspring.config.import=sub/e.properties,sub/shared.properties\n");
            String shared = await(app, 36, "e:1:shared:e|");
            assertNotNull(shared, agentLines(app));
            assertRestartEqual(classpath, null, shared);
            System.out.println("[r38-imports] classpath importer: startup import removed, nested-then-outer, config tree new line, a file reached twice: restart-equal 5/5");
        }
    }

    /** The agent's own lines; the probe lines would push them out of a plain tail. */
    private static String agentLines(WatchedApp app) {
        var lines = app.output().stream().filter(l -> l.contains("Reclazz")).toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 15), lines.size()));
    }

    private void assertRestartEqual(String classpath, Path main, String live) throws Exception {
        Path run = Files.createTempDirectory(fresh, "restart");
        if (main == null) {
            // Classpath layout: the restarted JVM gets the same config files in its own classes directory.
            Path from = tmp.resolve("classes"), to = Files.createDirectories(run.resolve("classes"));
            try (var files = Files.walk(from)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isDirectory(f) || f.toString().endsWith(".class")) continue;
                    Path target = to.resolve(from.relativize(f).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(f, target);
                }
            }
        }
        String[] jvm = main == null ? new String[]{"-Donce=true"} : new String[]{"-Dconfig.file=" + u(main), "-Donce=true"};
        try (var restarted = WatchedApp.in(run).classpath(classpath).agentArgs("startupDelaySec=1")
                .jvmArgs(jvm).with("App", APP).with("Settings", SETTINGS).start()) {
            restarted.awaitOrFail("ONCE=", "restarted application did not report");
            assertEquals(restarted.latest("ONCE=").substring(5), live, "a restart on the same files builds the same sources and values");
        }
    }

    /** A path as written into a config file: forward slashes, since a backslash is an escape in .properties (Windows). */
    private static String u(Path path) { return path.toString().replace('\\', '/'); }

    private String probe(WatchedApp app, int n) throws Exception {
        Files.createFile(tmp.resolve("probe" + n));
        app.awaitOrFail("P" + n + "=", "probe " + n + " missing");
        return app.latest("P" + n + "=").substring(("P" + n + "=").length());
    }

    /** Probes until the expected prefix shows up (the save is applied asynchronously). */
    private String await(WatchedApp app, int n, String prefix) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            String id = n + "_" + attempt++;
            Files.createFile(tmp.resolve("probe" + id));
            app.awaitOrFail("P" + id + "=", "probe " + id + " missing");
            String line = app.latest("P" + id + "=").substring(("P" + id + "=").length());
            if (line.startsWith(prefix)) { System.out.println("[r38-imports] P" + n + "=" + line); return line; }
            Thread.sleep(200);
        }
        return null;
    }

    private static final String SETTINGS = """
            package app;
            @org.springframework.boot.context.properties.ConfigurationProperties("svc")
            public class Settings {
                private String note; private int timeout; private String extra = "none";
                public String getNote() { return note; } public void setNote(String v) { note = v; }
                public int getTimeout() { return timeout; } public void setTimeout(int v) { timeout = v; }
                public String getExtra() { return extra; } public void setExtra(String v) { extra = v; }
            }
            """;
    private static final String APP = """
            package app;
            import java.nio.file.*;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.boot.context.properties.EnableConfigurationProperties(Settings.class)
            public class App {
                @org.springframework.beans.factory.annotation.Value("${svc.note}") String note;
                static String report(org.springframework.context.ConfigurableApplicationContext context) {
                    Settings s = context.getBean(Settings.class);
                    StringBuilder sources = new StringBuilder();
                    for (var source : context.getEnvironment().getPropertySources())
                        if (source.getName().startsWith("Config ")) sources.append(source.getName()).append(';');
                    return s.getNote() + ":" + s.getTimeout() + ":" + s.getExtra() + ":" + context.getBean(App.class).note + "|" + sources;
                }
                public static void main(String[] args) throws Exception {
                    var boot = new org.springframework.boot.SpringApplication(App.class);
                    boot.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
                    String file = System.getProperty("config.file");
                    var context = file == null ? boot.run() : boot.run("--spring.config.location=file:" + file);
                    if (Boolean.getBoolean("once")) { System.out.println("ONCE=" + report(context)); return; }
                    System.out.println("READY");
                    Path dir = Path.of(System.getProperty("probe.dir"));
                    java.util.Set<String> done = new java.util.HashSet<>();
                    while (true) {
                        try (var files = Files.list(dir)) {
                            for (Path p : (Iterable<Path>) files::iterator) {
                                String name = p.getFileName().toString();
                                if (name.startsWith("probe") && done.add(name))
                                    System.out.println("P" + name.substring(5) + "=" + report(context));
                            }
                        }
                        Thread.sleep(30);
                    }
                }
            }
            """;
}
