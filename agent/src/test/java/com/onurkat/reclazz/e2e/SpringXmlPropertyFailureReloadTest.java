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

class SpringXmlPropertyFailureReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedEditsPreserveRawValuesAndAllowCorrection(boolean child) throws Exception {
        Path xml = tmp.resolve("integrity.xml");
        Files.writeString(xml, document("old", "1", "a", "untouched"));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + xml, "-Dtest.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY=true", "initial native injection missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            for (int n = 1; n <= 2; n++) {
                save(app, xml, "old", "1", "missing", n, n);
                probe(app, n, "true:true:old:1:a:1:after" + n);
            }
            save(app, xml, "old", "invalid", "a", 3, 3);
            probe(app, 3, "true:true:old:1:a:1:after3");
            save(app, xml, "new", "2", "b", 4, 3);
            probe(app, 4, "true:false:new:2:b:2:after4");
            save(app, xml, "new", "2", "missing", 5, 4);
            probe(app, 5, "false:false:new:2:b:2:after5");
            app.awaitOrFail("RECREATED=true:new:2", "preserved definition cannot recreate after metadata invalidation");
        }
    }
    private void save(WatchedApp app, Path path, String label, String number, String ref, int count, int failures) throws Exception {
        Files.writeString(path, document(label, number, ref, "after" + count));
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && (reports(app) < count || failures(app) < failures)) Thread.sleep(25);
        assertEquals(count, reports(app), app.tail()); assertEquals(failures, failures(app), app.tail());
    }
    private long reports(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Spring XML integrity.xml") && s.contains("reloaded:")).count();
    }
    private long failures(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("property apply failed")).count();
    }
    private void probe(WatchedApp app, int n, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + n));
        app.awaitOrFail("PROBE" + n + "=", "property integrity probe missing");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=" + expected, line.substring(line.indexOf("PROBE")), app.tail());
    }
    private static String document(String label, String number, String ref, String other) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<bean id='a' class='java.lang.Object'/><bean id='b' class='java.lang.Object'/>"
                + "<bean id='settings' class='app.App$Settings'><property name='label' value='" + label + "'/>"
                + "<property name='number' value='" + number + "'/><property name='dependency' ref='" + ref + "'/></bean>"
                + "<bean id='unrelated' class='app.App$Settings'><property name='label' value='" + other + "'/></bean></beans>";
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
            import org.springframework.core.io.FileSystemResource;
            import org.springframework.beans.factory.config.RuntimeBeanReference;
            import org.springframework.beans.factory.config.TypedStringValue;
            public class App {
                public static class Settings {
                    public String label; public int number; public Object dependency;
                    public void setLabel(String value) { label = value; }
                    public void setNumber(int value) { number = value; }
                    public void setDependency(Object value) { dependency = value; }
                }
                public static void main(String[] args) throws Exception {
                    var context = new GenericApplicationContext();
                    var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs")));
                    context.refresh();
                    Settings held = context.getBean("settings", Settings.class);
                    Settings unrelated = context.getBean("unrelated", Settings.class);
                    Object a = context.getBean("a"), b = context.getBean("b");
                    System.out.println("READY=" + (held.dependency == a));
                    Path directory = Path.of(System.getProperty("test.dir"));
                    for (int n = 1; n <= 5; n++) {
                        while (!Files.exists(directory.resolve("probe" + n))) Thread.sleep(25);
                        if (n == 5) {
                            var factory = context.getDefaultListableBeanFactory();
                            factory.registerBeanDefinition("settings", factory.getBeanDefinition("settings"));
                            Settings recreated = context.getBean("settings", Settings.class);
                            System.out.println("RECREATED=" + (recreated.dependency == b) + ":" + recreated.label + ":" + recreated.number);
                        }
                        var values = context.getBeanFactory().getBeanDefinition("settings").getPropertyValues();
                        System.out.println("PROBE" + n + "="
                            + (held == context.getBean("settings") && unrelated == context.getBean("unrelated"))
                            + ":" + (held.dependency == a) + ":" + held.label + ":" + held.number
                            + ":" + ((RuntimeBeanReference) values.get("dependency")).getBeanName()
                            + ":" + ((TypedStringValue) values.get("number")).getValue() + ":" + unrelated.label);
                    }
                    while (true) Thread.sleep(100);
                }
            }
            """;
}
