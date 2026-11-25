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
 * R35: a getter/setter pair added after startup is a bean property for Spring's BeanWrapper (form binding, BeanUtils,
 * SAP Commerce ConversionHelper), even though Spring had already cached the class's properties. Removal drops it.
 */
class AddedBeanPropertySixReloadTest {
    @TempDir Path tmp;

    @Test void beanWrapperSeesAddedAndRemovedProperties() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Dto", dto(false)).start()) {
            app.awaitOrFail("READY", "app did not start");
            app.awaitWatcherReady();
            assertEquals("false|-|-", probe(app, "0"));
            app.rewrite("Dto", dto(true));
            assertEquals("true|x|y", await(app, 1, "true|x|y"), app.tail());
            app.rewrite("Dto", dto(false));
            assertEquals("false|-|-", await(app, 2, "false|-|-"), app.tail());
            System.out.println("[r35-beans-six] added getter/setter is a BeanWrapper property after a warm cache; removal drops it");
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
            import org.springframework.beans.*;
            public class App {
                static String report() {
                    Dto dto = new Dto();
                    BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(dto);
                    if (!wrapper.isReadableProperty("extra")) return "false|-|-";
                    Object before = wrapper.getPropertyValue("extra");
                    wrapper.setPropertyValue("extra", "y");
                    return "true|" + before + "|" + wrapper.getPropertyValue("extra");
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
