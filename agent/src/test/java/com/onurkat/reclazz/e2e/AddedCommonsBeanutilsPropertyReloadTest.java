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
 * R35: a getter/setter pair added after startup is a property for commons-beanutils' PropertyUtils after its descriptor
 * cache was filled. Removal drops it.
 */
class AddedCommonsBeanutilsPropertyReloadTest {
    @TempDir Path tmp;

    @Test void propertyUtilsSeesAddedAndRemovedProperties() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + java.io.File.pathSeparator + java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(p -> { String n = java.nio.file.Path.of(p).getFileName().toString(); return n.startsWith("commons-beanutils-") || n.startsWith("commons-collections-"); })
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator))).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Dto", dto(false)).start()) {
            app.awaitOrFail("READY", "app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            assertEquals("false|-|-", probe(app, "0"));
            app.rewrite("Dto", dto(true));
            assertEquals("true|x|y", await(app, 1, "true|x|y"), app.tail());
            app.rewrite("Dto", dto(false));
            assertEquals("false|-|-", await(app, 2, "false|-|-"), app.tail());
            System.out.println("[r35-beanutils] added getter/setter readable and writable by a warm PropertyUtils; removal drops it");
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
                    Dto dto = new Dto();
                    if (!org.apache.commons.beanutils.PropertyUtils.isReadable(dto, "extra")) return "false|-|-";
                    Object before = org.apache.commons.beanutils.PropertyUtils.getProperty(dto, "extra");
                    org.apache.commons.beanutils.PropertyUtils.setProperty(dto, "extra", "y");
                    return "true|" + before + "|" + org.apache.commons.beanutils.PropertyUtils.getProperty(dto, "extra");
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
