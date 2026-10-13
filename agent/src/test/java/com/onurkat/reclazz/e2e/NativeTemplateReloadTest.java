/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Actual parsed-template output; caching remains enabled throughout. */
class NativeTemplateReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nativeCacheKeepsOldRenderUntilExplicitClear(boolean thymeleaf) throws Exception {
        Path file = tmp.resolve(thymeleaf ? "view.html" : "view.ftl");
        Files.writeString(file, template(thymeleaf, "Before"));
        if (thymeleaf) {
            var engine = new org.thymeleaf.TemplateEngine();
            var resolver = new org.thymeleaf.templateresolver.FileTemplateResolver();
            resolver.setPrefix(tmp + File.separator); resolver.setSuffix(".html");
            resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
            resolver.setCharacterEncoding("UTF-8"); resolver.setCacheable(true);
            engine.setTemplateResolver(resolver);
            var context = new org.thymeleaf.context.Context(java.util.Locale.ROOT, java.util.Map.of("name", "Ada"));
            assertEquals("<p>Before Ada</p>", engine.process("view", context));
            Files.writeString(file, template(true, "Yeni"));
            assertEquals("<p>Before Ada</p>", engine.process("view", context), "native file save alone must stay cached");
            engine.clearTemplateCache();
            assertEquals("<p>Yeni Ada</p>", engine.process("view", context));
        } else {
            var engine = new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_34);
            engine.setDirectoryForTemplateLoading(tmp.toFile()); engine.setDefaultEncoding("UTF-8");
            engine.setTemplateUpdateDelayMilliseconds(Long.MAX_VALUE);
            assertEquals("Before Ada", render(engine));
            Files.writeString(file, template(false, "Yeni"));
            assertEquals("Before Ada", render(engine), "native file save alone must stay cached");
            engine.clearTemplateCache();
            assertEquals("Yeni Ada", render(engine));
        }
    }

    private static String render(freemarker.template.Configuration engine) throws Exception {
        var out = new java.io.StringWriter();
        engine.getTemplate("view.ftl").process(java.util.Map.of("name", "Ada"), out);
        return out.toString();
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void cachedNativeRenderFollowsWatchedSavesAndRecoversFromMalformedTemplate(boolean thymeleaf, boolean child) throws Exception {
        Path file = Files.createDirectories(tmp.resolve("classes")).resolve(thymeleaf ? "view.html" : "view.ftl");
        Files.writeString(file, template(thymeleaf, "Before"));
        var builder = WatchedApp.in(tmp).classpath(engineClasspath()).with("App", app(thymeleaf))
                .jvmArgs("-Dprobe.dir=" + tmp);
        if (child) builder.childClassLoader();
        try (var running = builder.start()) {
            running.awaitOrFail("READY=" + rendered(thymeleaf, "Before"), "native render did not warm its cache");
            running.awaitOrFail("] Watching ", "watcher missing");
            if (child) running.awaitOrFail("APP_IN_CHILD_MODULE=true", "child application loader missing");
            for (int n = 1; n <= 4; n++) {
                String label = n == 1 ? "Yeni" : n == 2 ? "Again" : "Recovered";
                Files.writeString(file, n == 3 ? (thymeleaf ? "<p th:text=\"${name + }\"></p>" : "Broken ${name + }") : template(thymeleaf, label));
                awaitClears(running, n);
                Files.createFile(tmp.resolve("probe" + n));
                running.awaitOrFail("PROBE" + n + "=", "native render probe missing");
                String actual = running.latest("PROBE" + n + "=");
                if (n == 3) assertTrue(actual.startsWith("PROBE3=ERROR:"), running.tail());
                else assertEquals("PROBE" + n + "=" + rendered(thymeleaf, label), actual, running.tail());
                System.out.println("[native-template] " + actual);
            }
        }
    }

    private static void awaitClears(WatchedApp running, int count) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && clears(running) < count) Thread.sleep(25);
        assertEquals(count, clears(running), running.tail());
    }
    private static long clears(WatchedApp running) {
        return running.output().stream().filter(v -> v.contains("Template cache cleared for view.")).count();
    }
    private static String template(boolean thymeleaf, String label) {
        return thymeleaf ? "<p th:text=\"'" + label + " ' + ${name}\">Fallback</p>" : label + " ${name}";
    }
    private static String rendered(boolean thymeleaf, String label) {
        return thymeleaf ? "<p>" + label + " Ada</p>" : label + " Ada";
    }
    private static String engineClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> {
                    String name = Path.of(p).getFileName().toString();
                    return name.startsWith("freemarker-") || name.startsWith("thymeleaf-") || name.startsWith("ognl-")
                            || name.startsWith("javassist-") || name.startsWith("attoparser-")
                            || name.startsWith("unbescape-") || name.startsWith("slf4j-api-");
                }).toList());
    }
    private static String app(boolean thymeleaf) {
        String engine = thymeleaf ? """
                var engine = new org.thymeleaf.TemplateEngine();
                var resolver = new org.thymeleaf.templateresolver.FileTemplateResolver();
                resolver.setPrefix(dir.resolve("classes") + java.io.File.separator); resolver.setSuffix(".html");
                resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
                resolver.setCharacterEncoding("UTF-8"); resolver.setCacheable(true);
                engine.setTemplateResolver(resolver);
                var context = new org.thymeleaf.context.Context(java.util.Locale.ROOT, java.util.Map.of("name", "Ada"));
                java.util.concurrent.Callable<String> render = () -> engine.process("view", context);
                """ : """
                var engine = new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_34);
                engine.setDirectoryForTemplateLoading(dir.resolve("classes").toFile()); engine.setDefaultEncoding("UTF-8");
                engine.setTemplateUpdateDelayMilliseconds(Long.MAX_VALUE);
                java.util.concurrent.Callable<String> render = () -> {
                    var out = new java.io.StringWriter();
                    engine.getTemplate("view.ftl").process(java.util.Map.of("name", "Ada"), out);
                    return out.toString();
                };
                """;
        return """
                package app;
                import java.nio.file.*;
                public class App {
                    public static void main(String[] args) throws Exception {
                        Path dir = Path.of(System.getProperty("probe.dir"));
                        %s
                        System.out.println("READY=" + render.call());
                        for (int n = 1; n <= 4; n++) {
                            while (!Files.exists(dir.resolve("probe" + n))) Thread.sleep(20);
                            try { System.out.println("PROBE" + n + "=" + render.call()); }
                            catch (Exception error) { System.out.println("PROBE" + n + "=ERROR:" + error.getClass().getSimpleName()); }
                        }
                    }
                }
                """.formatted(engine);
    }
}
