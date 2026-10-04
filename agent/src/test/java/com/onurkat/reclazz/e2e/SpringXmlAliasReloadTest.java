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

class SpringXmlAliasReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aliasOnlyEditsReportRestartAndPreserveEveryLookup(boolean child) throws Exception {
        Path defs = tmp.resolve("definitions.xml");
        Path aliases = Files.createDirectories(tmp.resolve("external")).resolve("aliases.xml");
        Files.writeString(defs, xml(bean("a") + bean("b")));
        Files.writeString(aliases, xml(alias("a", "chosen")));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + defs, "-Daliases=" + aliases, "-Dtest.dir=" + tmp)
                .with("App", APP).start()) {
            app.awaitOrFail("READY=true:1", "initial lookup or application event listener missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            save(app, aliases, alias("a", "chosen"), 1);
            assertFalse(app.output().stream().anyMatch(v -> v.contains("alias declaration")), app.tail());
            save(app, aliases, alias("b", "chosen"), 2);
            app.awaitOrFail("alias declaration retargeted", "retarget warning missing");
            probe(app, 1, "true:false:false:1");
            save(app, aliases, "", 3);
            app.awaitOrFail("alias declaration removed", "removal warning missing");
            probe(app, 2, "true:false:false:1");
            save(app, aliases, bean("c") + alias("c", "newName"), 4);
            app.awaitOrFail("alias declaration added", "new alias warning missing");
            probe(app, 3, "true:false:true:1");
            save(app, aliases, bean("c") + alias("c", "newName"), 5);
            probe(app, 4, "true:false:true:1");
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inlineAliasEventsAreCapturedWithoutReplacingApplicationListener(boolean child) throws Exception {
        Path defs = tmp.resolve("definitions.xml"), aliases = tmp.resolve("aliases.xml");
        Files.writeString(defs, xml(bean("a").replace("id='a'", "id='a' name='chosen'")));
        Files.writeString(aliases, xml(""));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + defs, "-Daliases=" + aliases, "-Dtest.dir=" + tmp)
                .with("App", APP).start()) {
            app.awaitOrFail("READY=true:0", "inline alias missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            Files.writeString(defs, xml(bean("a")));
            app.awaitOrFail("alias 'chosen' (alias declaration removed", "inline alias removal warning missing");
            probe(app, 1, "true:false:false:0");
        }
    }
    private void save(WatchedApp app, Path path, String content, int count) throws Exception {
        Files.writeString(path, xml(content));
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && reports(app) < count) Thread.sleep(25);
        assertEquals(count, reports(app), app.tail());
    }
    private long reports(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Spring XML aliases.xml")
                && (s.contains("reloaded:") || s.contains("parsed OK"))).count();
    }
    private void probe(WatchedApp app, int n, String value) throws Exception {
        Files.createFile(tmp.resolve("probe" + n));
        app.awaitOrFail("PROBE" + n + "=", "lookup probe missing");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=" + value, line.substring(line.indexOf("PROBE")), app.tail());
    }
    private static String xml(String body) { return "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>"; }
    private static String bean(String id) { return "<bean id='" + id + "' class='java.lang.Object'/>"; }
    private static String alias(String target, String name) { return "<alias name='" + target + "' alias='" + name + "'/>"; }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
            import org.springframework.beans.factory.parsing.*;
            import org.springframework.core.io.FileSystemResource;
            public class App {
                static int aliases;
                public static void main(String[] args) throws Exception {
                    var context = new GenericApplicationContext();
                    var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
                    reader.setEventListener(new EmptyReaderEventListener() {
                        @Override public void aliasRegistered(AliasDefinition definition) { aliases++; }
                    });
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs")));
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("aliases")));
                    context.refresh();
                    Object held = context.getBean("chosen");
                    System.out.println("READY=" + (held == context.getBean("a")) + ":" + aliases);
                    Path directory = Path.of(System.getProperty("test.dir"));
                    for (int n = 1; n <= 4; n++) {
                        while (!Files.exists(directory.resolve("probe" + n))) Thread.sleep(25);
                        System.out.println("PROBE" + n + "=" + (held == context.getBean("chosen"))
                            + ":" + context.containsBean("newName") + ":" + context.containsBean("c") + ":" + aliases);
                    }
                    while (true) Thread.sleep(100);
                }
            }
            """;
}
