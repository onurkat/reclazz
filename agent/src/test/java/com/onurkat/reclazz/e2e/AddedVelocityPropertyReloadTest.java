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
 * R35: a getter/setter pair added after startup is a property for a Velocity engine whose introspector cache already
 * holds the class (SAP Commerce email and CMS templates, ImpEx VelocityTranslator). Removal drops it. Uses the Velocity
 * jars of a local SAP Commerce installation (RECLAZZ_SAP_HOME or ~/Projects/reclazz-sap/hybris); skipped without one.
 */
class AddedVelocityPropertyReloadTest {
    @TempDir Path tmp;

    @Test void velocitySeesAddedAndRemovedProperties() throws Exception {
        Path lib = Path.of(System.getenv().getOrDefault("RECLAZZ_SAP_HOME", System.getProperty("user.home") + "/Projects/reclazz-sap/hybris"))
                .resolve("bin/platform/ext/core/lib");
        String jars = "";
        if (Files.isDirectory(lib)) try (var files = Files.list(lib)) {
            jars = files.map(Path::toString).filter(p -> { String n = Path.of(p).getFileName().toString();
                return n.startsWith("velocity-engine-core-") || n.startsWith("slf4j-api-") || n.startsWith("commons-lang3-"); })
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(jars.contains("velocity-engine-core-"), "no SAP Commerce Velocity jars at " + lib);
        try (var app = WatchedApp.in(tmp).classpath(jars).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Dto", dto(false)).start()) {
            app.awaitOrFail("READY", "app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            assertEquals("false|-|-", probe(app, "0"));
            app.rewrite("Dto", dto(true));
            assertEquals("true|x|y", await(app, 1, "true|x|y"), app.tail());
            app.rewrite("Dto", dto(false));
            assertEquals("false|-|-", await(app, 2, "false|-|-"), app.tail());
            System.out.println("[r35-velocity] added getter/setter readable and settable by a warm Velocity engine; removal drops it");
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
                static final org.apache.velocity.app.VelocityEngine ENGINE = new org.apache.velocity.app.VelocityEngine();
                static { ENGINE.init(); }
                static String render(Dto dto, String template) {
                    var context = new org.apache.velocity.VelocityContext();
                    context.put("dto", dto);
                    var out = new java.io.StringWriter();
                    ENGINE.evaluate(context, out, "probe", template);
                    return out.toString();
                }
                static String report() {
                    Dto dto = new Dto();
                    String before = render(dto, "$dto.extra");
                    if (before.equals("$dto.extra")) return "false|-|-";
                    return "true|" + before + "|" + render(dto, "#set($dto.extra = 'y')$dto.extra");
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
