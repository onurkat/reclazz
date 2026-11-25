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
 * R35: a getter added after startup is readable by a SpEL expression evaluated through an evaluation context whose
 * property accessor already cached the class's methods (`#{dto.extra}`, `@Value`, Thymeleaf-Spring). Removal drops it.
 */
class AddedSpelPropertyReloadTest {
    @TempDir Path tmp;

    @Test void anExistingEvaluationContextReadsAddedAndRemovedGetters() throws Exception {
        try (var app = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath()).agentArgs("startupDelaySec=1,debounceMs=100")
                .jvmArgs("-Dprobe.dir=" + tmp).with("App", APP).with("Dto", dto(false)).start()) {
            app.awaitOrFail("READY", "app did not start");
            app.awaitWatcherReady();
            assertEquals("false|-|-", probe(app, "0"));
            app.rewrite("Dto", dto(true));
            assertEquals("true|x|y", await(app, 1, "true|x|y"), app.tail());
            app.rewrite("Dto", dto(false));
            assertEquals("false|-|-", await(app, 2, "false|-|-"), app.tail());
            System.out.println("[r35-spel] added getter readable by a warm SpEL context; removal drops it");
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
                static final org.springframework.expression.spel.support.StandardEvaluationContext CONTEXT =
                        new org.springframework.expression.spel.support.StandardEvaluationContext();
                static final org.springframework.expression.Expression EXTRA =
                        new org.springframework.expression.spel.standard.SpelExpressionParser().parseExpression("extra");
                static final org.springframework.expression.Expression SET =
                        new org.springframework.expression.spel.standard.SpelExpressionParser().parseExpression("extra = 'y'");
                static String report() {
                    Dto dto = new Dto();
                    Object before;
                    try { before = EXTRA.getValue(CONTEXT, dto); } catch (org.springframework.expression.EvaluationException missing) { return "false|-|-"; }
                    SET.getValue(CONTEXT, dto);
                    return "true|" + before + "|" + EXTRA.getValue(CONTEXT, dto);
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
