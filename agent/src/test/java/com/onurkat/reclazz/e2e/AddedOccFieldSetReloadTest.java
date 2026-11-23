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
 * R35: SAP Commerce OCC lists a field added to a DTO after startup in {@code fields=FULL}, after DefaultFieldSetLevelHelper
 * computed and kept the FULL level for that DTO, while a level configured in XML (a FieldSetLevelMapping bean) stays.
 * Removal drops the field. Uses webservicescommonsserver.jar of a local SAP Commerce installation (RECLAZZ_SAP_HOME or
 * ~/Projects/reclazz-sap/hybris); skipped without one.
 */
class AddedOccFieldSetReloadTest {
    @TempDir Path tmp;

    @Test void fullFieldSetFollowsAddedAndRemovedFields() throws Exception {
        Path jar = Path.of(System.getenv().getOrDefault("RECLAZZ_SAP_HOME", System.getProperty("user.home") + "/Projects/reclazz-sap/hybris"))
                .resolve("bin/modules/web-services-commons/webservicescommons/bin/webservicescommonsserver.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(jar), "no SAP Commerce webservicescommons at " + jar);
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath() + java.io.File.pathSeparator + jar).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Dto", dto(false)).start()) {
            app.awaitOrFail("READY", "app did not start");
            app.awaitOrFail("] Watching ", "watcher did not start");
            assertEquals("name|name", probe(app, "0"));
            app.rewrite("Dto", dto(true));
            assertEquals("name,extra|name", await(app, 1, "name,extra|name"), app.tail());
            app.rewrite("Dto", dto(false));
            assertEquals("name|name", await(app, 2, "name|name"), app.tail());
            System.out.println("[r35-occ] fields=FULL lists an added DTO field after a cached FULL level; XML level kept; removal drops it");
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
                static final de.hybris.platform.webservicescommons.mapping.impl.DefaultFieldSetLevelHelper HELPER;
                static {
                    var context = new org.springframework.context.support.GenericApplicationContext();
                    context.registerBean("mapping", de.hybris.platform.webservicescommons.mapping.config.FieldSetLevelMapping.class, () -> {
                        var mapping = new de.hybris.platform.webservicescommons.mapping.config.FieldSetLevelMapping();
                        mapping.setDtoClass(Dto.class);
                        mapping.setLevelMapping(new java.util.HashMap<>(java.util.Map.of("CUSTOM", "name")));
                        return mapping;
                    });
                    context.refresh();
                    HELPER = new de.hybris.platform.webservicescommons.mapping.impl.DefaultFieldSetLevelHelper();
                    HELPER.setApplicationContext(context);
                }
                static String report() {
                    String full = HELPER.getLevelDefinitionForClass(Dto.class, "FULL");
                    if (full == null) full = HELPER.createFullLevelDefinition(Dto.class);
                    return full + "|" + HELPER.getLevelDefinitionForClass(Dto.class, "CUSTOM");
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
