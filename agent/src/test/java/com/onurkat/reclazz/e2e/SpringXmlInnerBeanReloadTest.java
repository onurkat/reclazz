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

class SpringXmlInnerBeanReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void innerNoOpsEditsAndResourceRefusalsFollowWatcher(boolean child) throws Exception {
        Path xml = tmp.resolve("inner.xml"); Files.writeString(xml, document("old", "old", "before", "before"));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + xml, "-Dtest.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY=true", "native inner bean missing"); app.awaitOrFail("] Watching ", "watcher missing");
            save(app, xml, "old", "old", "before", "before", 1); probe(app, 1, "true:true:old:before:before:1:1:0");
            save(app, xml, "new", "old", "after", "after", 2); probe(app, 2, "true:true:new:after:after:1:1:0");
            save(app, xml, "new", "old", "after", "after", 3); probe(app, 3, "true:true:new:after:after:1:1:0");
            for (int n = 4; n <= 5; n++) {
                save(app, xml, "new", "rejected", "rejected", "other" + n, n);
                probe(app, n, "true:true:new:after:other" + n + ":1:1:0");
                long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < until && warnings(app) < n - 3) Thread.sleep(25);
                assertEquals(n - 3, warnings(app), app.tail());
            }
            Files.createFile(tmp.resolve("close")); app.awaitOrFail("CLOSED=1:1:1", "only original resource must close");
        }
    }
    private void save(WatchedApp app, Path xml, String plain, String resource, String label, String other, int count) throws Exception {
        Files.writeString(xml, document(plain, resource, label, other));
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && reports(app) < count) Thread.sleep(25);
        assertEquals(count, reports(app), app.tail());
    }
    private long reports(WatchedApp app) { return app.output().stream().filter(s -> s.contains("Spring XML inner.xml") && (s.contains("reloaded:") || s.contains("parsed OK"))).count(); }
    private long warnings(WatchedApp app) { return app.output().stream().filter(s -> s.contains("cannot safely compare/apply raw property values")).count(); }
    private void probe(WatchedApp app, int n, String expected) throws Exception {
        Files.createFile(tmp.resolve("probe" + n)); app.awaitOrFail("PROBE" + n + "=", "inner probe missing");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=" + expected, line.substring(line.indexOf("PROBE")), app.tail());
    }
    private static String document(String plain, String resource, String label, String other) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<bean id='plain' class='app.App$Owner'><property name='stage'><bean id='inner' class='app.App$Stage'><property name='value' value='" + plain + "'/></bean></property></bean>"
                + "<bean id='resource' class='app.App$Owner'><property name='label' value='" + label + "'/><property name='stage'><bean class='app.App$Resource'><property name='value' value='" + resource + "'/></bean></property></bean>"
                + "<bean id='other' class='app.App$Owner'><property name='label' value='" + other + "'/></bean></beans>";
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.beans.factory.InitializingBean;
            import org.springframework.beans.factory.DisposableBean;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
            import org.springframework.core.io.FileSystemResource;
            public class App {
                public static class Stage { public String value; public void setValue(String value) { this.value = value; } }
                public static class Resource extends Stage implements InitializingBean, DisposableBean {
                    static int created, initialized, destroyed;
                    public Resource() { created++; } public void afterPropertiesSet() { initialized++; } public void destroy() { destroyed++; }
                }
                public static class Owner { public Stage stage; public String label; public void setStage(Stage stage) { this.stage = stage; } public void setLabel(String value) { label = value; } }
                public static void main(String[] args) throws Exception {
                    var context = new GenericApplicationContext(); var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs"))); context.refresh();
                    Owner plain = context.getBean("plain", Owner.class), resource = context.getBean("resource", Owner.class), other = context.getBean("other", Owner.class);
                    Stage originalResource = resource.stage;
                    Object raw = context.getBeanFactory().getBeanDefinition("resource").getPropertyValues().get("stage");
                    System.out.println("READY=" + (Resource.created == 1 && Resource.initialized == 1));
                    Path directory = Path.of(System.getProperty("test.dir"));
                    for (int n = 1; n <= 5; n++) {
                        while (!Files.exists(directory.resolve("probe" + n))) Thread.sleep(25);
                        System.out.println("PROBE" + n + "=" + (plain == context.getBean("plain") && resource == context.getBean("resource") && other == context.getBean("other"))
                            + ":" + (originalResource == resource.stage && raw == context.getBeanFactory().getBeanDefinition("resource").getPropertyValues().get("stage") && resource.stage.value.equals("old"))
                            + ":" + plain.stage.value + ":" + resource.label + ":" + other.label + ":" + Resource.created + ":" + Resource.initialized + ":" + Resource.destroyed);
                    }
                    while (!Files.exists(directory.resolve("close"))) Thread.sleep(25);
                    context.close(); System.out.println("CLOSED=" + Resource.created + ":" + Resource.initialized + ":" + Resource.destroyed);
                    while (true) Thread.sleep(100);
                }
            }
            """;
}
