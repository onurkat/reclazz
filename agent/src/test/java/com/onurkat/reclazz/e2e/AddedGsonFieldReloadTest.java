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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R35: a field added to a class after startup is serialized and deserialized by a Gson instance that already cached an
 * adapter for that class, on a stock JVM where the field lives outside the JVM class. Removing it again drops it.
 */
class AddedGsonFieldReloadTest {
    @TempDir Path tmp;

    @Test void anExistingGsonSeesAddedAndRemovedFields() throws Exception {
        String gson = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("gson-")).findFirst().orElseThrow();
        try (var app = WatchedApp.in(tmp).classpath(gson).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Dto", dto(false)).start()) {
            app.awaitOrFail("READY", "app did not start");
            app.awaitWatcherReady();
            assertEquals("{\"name\":\"a\"}|n", probe(app, 0));

            app.rewrite("Dto", dto(true));
            assertEquals("{\"name\":\"a\",\"added\":\"x\",\"label\":\"r\"}|n/in", await(app, 1, "{\"name\":\"a\",\"added\":\"x\",\"label\":\"r\"}|n/in"), app.tail());

            app.rewrite("Dto", dto(false));
            assertEquals("{\"name\":\"a\"}|n", await(app, 2, "{\"name\":\"a\"}|n"), app.tail());
            System.out.println("[r35-gson] added field serialized and deserialized by a cached Gson; removal dropped it");
        }
    }

    private String probe(WatchedApp app, String id) throws Exception {
        Files.createFile(tmp.resolve("probe" + id));
        app.awaitOrFail("G" + id + "=", "probe " + id + " missing");
        return app.latest("G" + id + "=").substring(("G" + id + "=").length());
    }
    private String probe(WatchedApp app, int n) throws Exception { return probe(app, String.valueOf(n)); }

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
        return "package app;\npublic class Dto {\n    String name = \"a\";\n"
                + (added ? "    String added = \"x\";\n    @com.google.gson.annotations.SerializedName(\"label\") String renamed = \"r\";\n"
                        + "    transient String skipped = \"s\";\n" : "")
                + "    public String describe() { return name" + (added ? " + \"/\" + added" : "") + "; }\n}\n";
    }

    private static final String APP = """
            package app;
            import java.nio.file.*;
            public class App {
                public static void main(String[] args) throws Exception {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    gson.toJson(new Dto());
                    System.out.println("READY");
                    Path dir = Path.of(System.getProperty("probe.dir"));
                    java.util.Set<String> done = new java.util.HashSet<>();
                    while (true) {
                        try (var files = Files.list(dir)) {
                            for (Path p : (Iterable<Path>) files::iterator) {
                                String name = p.getFileName().toString();
                                if (name.startsWith("probe") && done.add(name)) {
                                    String out;
                                    try {
                                        out = gson.toJson(new Dto()) + "|" + gson.fromJson("{\\"name\\":\\"n\\",\\"added\\":\\"in\\"}", Dto.class).describe();
                                    } catch (Throwable t) { out = "ERROR " + t; }
                                    System.out.println("G" + name.substring(5) + "=" + out);
                                }
                            }
                        }
                        Thread.sleep(30);
                    }
                }
            }
            """;
}
