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

class SpringXmlDefinitionMetadataReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void metadataEditsWarnBeforePropertiesAndRepeatedSavesRemainSafe(boolean child) throws Exception {
        Path xml = tmp.resolve("metadata.xml");
        Files.writeString(xml, document(false, "before", "before"));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + xml, "-Dtest.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY=true", "native initial metadata is missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            save(app, xml, false, "before", "before", 1);
            assertEquals(0, warnings(app), app.tail());
            for (int n = 2; n <= 4; n++) {
                save(app, xml, true, "rejected", "other" + n, n);
                long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < until && warnings(app) < n - 1) Thread.sleep(25);
                assertEquals(n - 1, warnings(app), app.tail());
                assertTrue(app.output().stream().anyMatch(s -> s.contains("definition metadata changed (depends-on, primary, autowire, autowire-candidate)")), app.tail());
                probe(app, n, "true:true:before:other" + n);
            }
            save(app, xml, false, "corrected", "final", 5);
            probe(app, 5, "true:true:corrected:final");
            assertEquals(3, warnings(app), app.tail());
        }
    }
    private void save(WatchedApp app, Path path, boolean changed, String label, String other, int count) throws Exception {
        Files.writeString(path, document(changed, label, other));
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && reports(app) < count) Thread.sleep(25);
        assertEquals(count, reports(app), app.tail());
    }
    private long reports(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Spring XML metadata.xml")
                && (s.contains("reloaded:") || s.contains("parsed OK"))).count();
    }
    private long warnings(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("definition metadata changed")).count();
    }
    private void probe(WatchedApp app, int n, String value) throws Exception {
        Files.createFile(tmp.resolve("probe" + n));
        app.awaitOrFail("PROBE" + n + "=", "metadata probe missing");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=" + value, line.substring(line.indexOf("PROBE")), app.tail());
    }
    private static String document(boolean changed, String label, String other) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<bean id='a' class='app.App$First'/><bean id='b' class='java.lang.Object'/>"
                + "<bean id='settings' class='app.App$Settings' depends-on='" + (changed ? "b" : "a") + "'"
                + (changed ? " primary='true' autowire='byType' autowire-candidate='false'" : "")
                + "><property name='label' value='" + label + "'/></bean>"
                + "<bean id='unrelated' class='app.App$Settings'><property name='label' value='" + other + "'/></bean></beans>";
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import java.util.Arrays;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
            import org.springframework.beans.factory.support.AbstractBeanDefinition;
            import org.springframework.core.io.FileSystemResource;
            public class App {
                public static class First { }
                public static class Settings {
                    public String label;
                    public First first;
                    public void setLabel(String value) { label = value; }
                    public void setFirst(First value) { first = value; }
                }
                public static void main(String[] args) throws Exception {
                    var context = new GenericApplicationContext();
                    var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs")));
                    context.refresh();
                    Settings held = context.getBean("settings", Settings.class);
                    Settings unrelated = context.getBean("unrelated", Settings.class);
                    var factory = context.getBeanFactory();
                    AbstractBeanDefinition raw = (AbstractBeanDefinition) factory.getBeanDefinition("settings");
                    Object merged = factory.getMergedBeanDefinition("settings");
                    System.out.println("READY=" + (held.first == null && Arrays.equals(raw.getDependsOn(), new String[]{"a"}) && !raw.isPrimary()));
                    Path directory = Path.of(System.getProperty("test.dir"));
                    for (int n = 2; n <= 5; n++) {
                        while (!Files.exists(directory.resolve("probe" + n))) Thread.sleep(25);
                        System.out.println("PROBE" + n + "="
                            + (held == context.getBean("settings") && unrelated == context.getBean("unrelated"))
                            + ":" + (raw == factory.getBeanDefinition("settings") && merged == factory.getMergedBeanDefinition("settings")
                                && Arrays.equals(raw.getDependsOn(), new String[]{"a"}) && !raw.isPrimary()
                                && raw.getAutowireMode() == 0 && raw.isAutowireCandidate() && held.first == null)
                            + ":" + held.label + ":" + unrelated.label);
                    }
                    while (true) Thread.sleep(100);
                }
            }
            """;
}
