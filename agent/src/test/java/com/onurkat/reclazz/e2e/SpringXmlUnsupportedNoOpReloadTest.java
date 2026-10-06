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

class SpringXmlUnsupportedNoOpReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unchangedShapesStayQuietAndRealEditsStillWarn(boolean child) throws Exception {
        Path xml = tmp.resolve("unsupported.xml"); Files.writeString(xml, document("before", "before"));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + xml, "-Dtest.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY=true", "native unsupported fixtures missing"); app.awaitOrFail("] Watching ", "watcher missing");
            for (int n = 1; n <= 3; n++) {
                String other = n == 1 ? "before" : "other" + n;
                save(app, xml, "before", other, n); probe(app, n, other);
                assertEquals(0, warnings(app), app.tail());
            }
            for (int n = 4; n <= 5; n++) {
                save(app, xml, "rejected", "other" + n, n); probe(app, n, "other" + n);
                long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < until && warnings(app) < n - 3) Thread.sleep(25);
                assertEquals(n - 3, warnings(app), app.tail());
                assertTrue(app.output().stream().anyMatch(v -> v.contains("3 require restart")), app.tail());
            }
            Files.createFile(tmp.resolve("close")); app.awaitOrFail("CLOSED=1", "original lifecycle resource must close once");
        }
    }
    private void save(WatchedApp app, Path xml, String unsupported, String other, int count) throws Exception {
        Files.writeString(xml, document(unsupported, other));
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && reports(app) < count) Thread.sleep(25);
        assertEquals(count, reports(app), app.tail());
    }
    private long reports(WatchedApp app) { return app.output().stream().filter(s -> s.contains("Spring XML unsupported.xml") && (s.contains("reloaded:") || s.contains("parsed OK"))).count(); }
    private long warnings(WatchedApp app) { return app.output().stream().filter(s -> s.contains("Restart required")).count(); }
    private void probe(WatchedApp app, int n, String other) throws Exception {
        Files.createFile(tmp.resolve("probe" + n)); app.awaitOrFail("PROBE" + n + "=", "unsupported probe missing");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=true:before:before:" + other + ":1:0:3", line.substring(line.indexOf("PROBE")), app.tail());
    }
    private static String document(String value, String other) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<bean id='template' class='app.App$Settings' abstract='true'><property name='label' value='" + value + "'/></bean>"
                + "<bean id='proto' class='app.App$Settings' scope='prototype'><property name='label' value='" + value + "'/></bean>"
                + "<bean id='resource' class='app.App$Resource' init-method='init'><property name='label' value='" + value + "'/></bean>"
                + "<bean id='other' class='app.App$Settings'><property name='label' value='" + other + "'/></bean></beans>";
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.beans.factory.InitializingBean;
            import org.springframework.beans.factory.DisposableBean;
            import org.springframework.beans.factory.config.TypedStringValue;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
            import org.springframework.core.io.FileSystemResource;
            public class App {
                public static class Settings { static int created; public String label; public Settings() { created++; } public void setLabel(String value) { label = value; } public void init() { } }
                public static class Resource extends Settings implements InitializingBean, DisposableBean { static int initialized, destroyed; public void afterPropertiesSet() { initialized++; } public void destroy() { destroyed++; } }
                public static void main(String[] args) throws Exception {
                    var context = new GenericApplicationContext(); var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs"))); context.refresh();
                    Settings resource = context.getBean("resource", Settings.class), other = context.getBean("other", Settings.class), proto = context.getBean("proto", Settings.class);
                    var factory = context.getBeanFactory(); Object templateDef = factory.getBeanDefinition("template"), protoDef = factory.getBeanDefinition("proto"), resourceDef = factory.getBeanDefinition("resource");
                    System.out.println("READY=" + (Settings.created == 3 && Resource.initialized == 1));
                    Path dir = Path.of(System.getProperty("test.dir"));
                    for (int n = 1; n <= 5; n++) {
                        while (!Files.exists(dir.resolve("probe" + n))) Thread.sleep(25);
                        boolean intact = resource == context.getBean("resource") && other == context.getBean("other")
                            && templateDef == factory.getBeanDefinition("template") && protoDef == factory.getBeanDefinition("proto") && resourceDef == factory.getBeanDefinition("resource")
                            && ((TypedStringValue) factory.getBeanDefinition("template").getPropertyValues().get("label")).getValue().equals("before")
                            && ((TypedStringValue) factory.getBeanDefinition("proto").getPropertyValues().get("label")).getValue().equals("before");
                        System.out.println("PROBE" + n + "=" + intact + ":" + resource.label + ":" + proto.label + ":" + other.label + ":" + Resource.initialized + ":" + Resource.destroyed + ":" + Settings.created);
                    }
                    while (!Files.exists(dir.resolve("close"))) Thread.sleep(25);
                    context.close(); System.out.println("CLOSED=" + Resource.destroyed);
                    while (true) Thread.sleep(100);
                }
            }
            """;
}
