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

/**
 * R35: SAP Commerce ConversionHelper.copyProperties copies a getter/setter pair added after startup, after its static
 * property descriptor cache already held the class. Removal drops it. Uses coreserver.jar of a local SAP Commerce
 * installation (RECLAZZ_SAP_HOME or ~/Projects/reclazz-sap/hybris); skipped without one.
 */
class AddedConversionHelperPropertyReloadTest {
    @TempDir Path tmp;

    @Test void copyPropertiesCopiesAddedAndRemovedProperties() throws Exception {
        Path core = Path.of(System.getenv().getOrDefault("RECLAZZ_SAP_HOME", System.getProperty("user.home") + "/Projects/reclazz-sap/hybris"))
                .resolve("bin/platform/ext/core/bin/coreserver.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(core), "no SAP Commerce coreserver.jar at " + core);
        String guava;
        try (var files = Files.list(core.getParent().getParent().resolve("lib"))) {
            guava = files.map(Path::toString).filter(p -> Path.of(p).getFileName().toString().startsWith("guava-")).findFirst().orElse("");
        }
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + java.io.File.pathSeparator + core + java.io.File.pathSeparator + guava).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Dto", dto(false)).start()) {
            app.awaitOrFail("READY", "app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            assertEquals("false|-|-", probe(app, "0"));
            app.rewrite("Dto", dto(true));
            assertEquals("true|z|x", await(app, 1, "true|z|x"), app.tail());
            app.rewrite("Dto", dto(false));
            assertEquals("false|-|-", await(app, 2, "false|-|-"), app.tail());
            System.out.println("[r35-conversionhelper] added property copied by a warm ConversionHelper; removal drops it");
        }
    }

    private String probe(WatchedApp app, String id) throws Exception {
        Files.createFile(tmp.resolve("probe" + id));
        app.awaitOrFail("B" + id + "=", "probe " + id + " missing");
        return app.latest("B" + id + "=").substring(("B" + id + "=").length());
    }

    private String await(WatchedApp app, int n, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String last = null;
        for (int attempt = 0; System.nanoTime() < deadline; attempt++) {
            last = probe(app, n + "_" + attempt);
            if (last.equals(expected)) return last;
            Thread.sleep(200);
        }
        return last;
    }

    private static String dto(boolean added) {
        return "package app;\npublic class Dto {\n    private String name = \"a\";\n"
                + "    public String getName() { return name; }\n    public void setName(String v) { name = v; }\n"
                + (added ? "    private String extra = \"x\";\n    public String getExtra() { return extra; }\n"
                        + "    public void setExtra(String v) { extra = v; }\n" : "")
                + "}\n";
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
                        public class App {
                static String report() throws Exception {
                    java.lang.reflect.Method get, set;
                    try { get = Dto.class.getMethod("getExtra"); set = Dto.class.getMethod("setExtra", String.class); }
                    catch (NoSuchMethodException absent) { de.hybris.platform.servicelayer.dto.converter.ConversionHelper.copyProperties(new Dto(), new Dto()); return "false|-|-"; }
                    Dto source = new Dto(), target = new Dto();
                    set.invoke(target, "z");
                    Object before = get.invoke(target);
                    de.hybris.platform.servicelayer.dto.converter.ConversionHelper.copyProperties(source, target);
                    return "true|" + before + "|" + get.invoke(target);
                }
                public static void main(String[] args) throws Exception {
                    report();
                    System.out.println("READY");
                    Path dir = Path.of(System.getProperty("probe.dir"));
                    java.util.Set<String> done = new java.util.HashSet<>();
                    while (true) {
                        try (var files = Files.list(dir)) {
                            for (Path p : (Iterable<Path>) files::iterator) {
                                String name = p.getFileName().toString();
                                if (name.startsWith("probe") && done.add(name)) {
                                    String out;
                                    try { out = report(); } catch (Throwable t) { out = "ERROR " + t; }
                                    System.out.println("B" + name.substring(5) + "=" + out);
                                }
                            }
                        }
                        Thread.sleep(30);
                    }
                }
            }
            """;
}
