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

class SpringXmlLookupMethodReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lookupChangesPreserveFactoryAndReportRestart(boolean child) throws Exception {
        Path xml = tmp.resolve("lookup.xml");
        Files.writeString(xml, document("a", "before", "before"));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + xml, "-Dtest.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY=true", "native lookup is missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            save(app, xml, "a", "before", "before", 1);
            assertEquals(0, warnings(app), app.tail());
            for (int n = 2; n <= 4; n++) {
                save(app, xml, n == 4 ? null : "b", "after", "after" + n, n);
                long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < until && warnings(app) < n - 1) Thread.sleep(25);
                assertEquals(n - 1, warnings(app), app.tail());
                probe(app, n, "true:true:before:after" + n);
            }
            // Restore the original lookup declaration; its ordinary property can update.
            save(app, xml, "a", "final", "final", 5);
            probe(app, 5, "true:true:final:final");
            assertEquals(3, warnings(app), app.tail());
        }
    }
    private void save(WatchedApp app, Path path, String target, String label, String other, int count) throws Exception {
        Files.writeString(path, document(target, label, other));
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && reports(app) < count) Thread.sleep(25);
        assertEquals(count, reports(app), app.tail());
    }
    private long reports(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Spring XML lookup.xml")
                && (s.contains("reloaded:") || s.contains("parsed OK"))).count();
    }
    private long warnings(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("lookup-method declarations changed")).count();
    }
    private void probe(WatchedApp app, int n, String value) throws Exception {
        Files.createFile(tmp.resolve("probe" + n));
        app.awaitOrFail("PROBE" + n + "=", "lookup probe missing");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=" + value, line.substring(line.indexOf("PROBE")), app.tail());
    }
    private static String document(String target, String label, String other) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<bean id='a' class='java.lang.Object'/><bean id='b' class='java.lang.Object'/>"
                + "<bean id='factory' class='app.App$Factory'>"
                + (target == null ? "" : "<lookup-method name='select' bean='" + target + "'/>")
                + "<property name='label' value='" + label + "'/></bean>"
                + "<bean id='unrelated' class='app.App$Factory'><property name='label' value='" + other + "'/></bean></beans>";
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
            import org.springframework.core.io.FileSystemResource;
            public class App {
                public static class Factory {
                    public String label;
                    public Object select() { return null; }
                    public void setLabel(String value) { label = value; }
                }
                public static void main(String[] args) throws Exception {
                    var context = new GenericApplicationContext();
                    var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs")));
                    context.refresh();
                    Factory held = context.getBean("factory", Factory.class);
                    Factory unrelated = context.getBean("unrelated", Factory.class);
                    Object original = context.getBean("a");
                    System.out.println("READY=" + (held.select() == original));
                    Path directory = Path.of(System.getProperty("test.dir"));
                    for (int n = 2; n <= 5; n++) {
                        while (!Files.exists(directory.resolve("probe" + n))) Thread.sleep(25);
                        System.out.println("PROBE" + n + "="
                            + (held == context.getBean("factory") && unrelated == context.getBean("unrelated"))
                            + ":" + (held.select() == original) + ":" + held.label + ":" + unrelated.label);
                    }
                    while (true) Thread.sleep(100);
                }
            }
            """;
}
