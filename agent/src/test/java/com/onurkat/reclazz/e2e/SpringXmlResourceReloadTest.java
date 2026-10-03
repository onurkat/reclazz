/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SpringXmlResourceReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void loadedImportsReloadOutsideWatchedRootsWithoutChangingAnotherContext(boolean child) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("classes")).resolve("web-application-config.xml");
        Path imported = Files.createDirectories(tmp.resolve("web config/nested")).resolve("spring-mvc-config.xml");
        Path middle = imported.getParent().getParent().resolve("imports.xml");
        Files.writeString(imported, bean("target", "before", false));
        Files.writeString(middle, beans("<import resource='nested/spring-mvc-config.xml'/>"
                + definition("rootValue", "root-before", false)));
        Files.writeString(root, rootXml(middle, null));
        Path other = Files.createDirectories(tmp.resolve("other")).resolve("spring-mvc-config.xml");
        Files.writeString(other, bean("target", "other", false));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Dxml.file=" + root, "-Dother.file=" + other,
                "-Dtest.dir=" + tmp).with("App", APP).with("Value", VALUE).start()) {
            app.awaitOrFail("READY=before:1:root-before:other", "initial XML missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            Files.writeString(imported, bean("target", "after", false));
            awaitReport(app, "spring-mvc-config.xml", 1);
            probe(app, 1, "after:2:root-before:other");
            Files.writeString(imported, bean("target", "after", false));
            awaitReport(app, "spring-mvc-config.xml", 2);
            probe(app, 2, "after:2:root-before:other");
            Files.writeString(root, rootXml(middle, "root-after"));
            awaitReport(app, "web-application-config.xml", 1);
            probe(app, 3, "after:2:root-after:other");
            Files.writeString(imported, bean("target", "refused", true));
            awaitReport(app, "spring-mvc-config.xml", 3);
            app.awaitOrFail("scope=prototype", "unsafe XML must still be refused");
            probe(app, 4, "after:2:root-after:other");
        }
    }

    private void probe(WatchedApp app, int n, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + n));
        app.awaitOrFail("PROBE" + n + "=", "missing value probe");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=" + expected, line.substring(line.indexOf("PROBE")), app.tail());
    }

    private void awaitReport(WatchedApp app, String name, int count) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && reports(app, name) < count) Thread.sleep(25);
        assertEquals(count, reports(app, name), app.tail());
    }

    private long reports(WatchedApp app, String name) {
        return app.output().stream().filter(s -> s.contains("Spring XML " + name)
                && (s.contains(" reloaded:") || s.contains("parsed OK"))).count();
    }

    private static String beans(String body) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>";
    }
    private static String definition(String id, String value, boolean prototype) {
        return "<bean id='" + id + "' class='app.Value'" + (prototype ? " scope='prototype'" : "")
                + "><property name='text' value='" + value + "'/></bean>";
    }
    private static String bean(String id, String value, boolean prototype) {
        return beans(definition(id, value, prototype));
    }
    private static String rootXml(Path middle, String value) {
        return beans("<import resource='" + middle.toUri() + "'/>" + (value == null ? "" : definition("rootValue", value, false)));
    }

    private static final String VALUE = """
            package app;
            public class Value {
                public String text;
                public int calls;
                public void setText(String value) { text = value; calls++; }
            }
            """;
    private static final String APP = """
            package app;
            public class App {
                private static org.springframework.context.support.GenericApplicationContext load(String file) {
                    var ctx = new org.springframework.context.support.GenericApplicationContext();
                    var reader = new org.springframework.beans.factory.xml.XmlBeanDefinitionReader(ctx);
                    reader.setValidating(false);
                    reader.loadBeanDefinitions(new org.springframework.core.io.FileSystemResource(file));
                    ctx.refresh(); return ctx;
                }
                private static String values(Value value, Value root, Value other) {
                    return value.text + ":" + value.calls + ":" + root.text + ":" + other.text;
                }
                public static void main(String[] args) throws Exception {
                    try (var other = load(System.getProperty("other.file"));
                         var ctx = load(System.getProperty("xml.file"))) {
                        Value value = ctx.getBean("target", Value.class);
                        Value root = ctx.getBean("rootValue", Value.class);
                        Value untouched = other.getBean("target", Value.class);
                        System.out.println("READY=" + values(value, root, untouched));
                        var dir = java.nio.file.Path.of(System.getProperty("test.dir"));
                        for (int i = 1; i <= 4; i++) {
                            while (!java.nio.file.Files.exists(dir.resolve("probe" + i))) Thread.sleep(25);
                            System.out.println("PROBE" + i + "=" + values(value, root, untouched));
                        }
                        Thread.sleep(1000);
                    }
                }
            }
            """;
}
