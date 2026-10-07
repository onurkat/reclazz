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

class SpringXmlLazyInitReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void watchedLazyMetadataPreservesCreationStateAndDefaultsRemainEquivalent(boolean child) throws Exception {
        Path xml = tmp.resolve("lazy.xml"); Files.writeString(xml, document(0));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + xml, "-Dtest.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY=true", "initial native creation state missing"); app.awaitOrFail("] Watching ", "watcher missing");
            for (int n = 1; n <= 6; n++) {
                Files.writeString(xml, document(n));
                long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < until && reports(app) < n) Thread.sleep(25);
                assertEquals(n, reports(app), app.tail());
                Files.createFile(tmp.resolve("probe" + n)); app.awaitOrFail("PROBE" + n + "=", "state probe missing");
                String value = n < 4 ? "before" : "corrected";
                String line = app.latest("PROBE" + n + "=");
                assertEquals("PROBE" + n + "=true:true:true:1:" + value + ":" + value + ":other" + n,
                        line.substring(line.indexOf("PROBE")), app.tail());
            }
            app.awaitOrFail("CREATED=true:corrected:2", "native creation after refused edits is incorrect");
            assertEquals(2, app.output().stream().filter(v -> v.contains("Restart required")).count(), app.tail());
            for (String name : new String[]{"warm", "cold"})
                assertEquals(2, app.output().stream().filter(v -> v.contains("- " + name + " (definition metadata changed (lazy-init)")).count(), app.tail());
        }
    }
    private long reports(WatchedApp app) {
        return app.output().stream().filter(v -> v.contains("Spring XML lazy.xml") && (v.contains("reloaded:") || v.contains("parsed OK"))).count();
    }
    private static String document(int n) {
        boolean rejected = n == 2 || n == 3, rootLazy = n == 5;
        String warm = rejected ? "true" : (n == 1 || n == 6 ? "default" : "false");
        String cold = rejected ? "false" : (n == 5 ? "default" : "true");
        String label = n >= 4 ? "corrected" : n == 2 ? "rejected" : "before";
        return "<beans xmlns='http://www.springframework.org/schema/beans' default-lazy-init='" + rootLazy + "'>"
                + "<bean id='warm' class='app.App$Target' lazy-init='" + warm + "'><property name='label' value='" + label + "'/></bean>"
                + "<bean id='cold' class='app.App$Target' lazy-init='" + cold + "'><property name='label' value='" + label + "'/></bean>"
                + "<bean id='other' class='app.App$Other' lazy-init='false'><property name='label' value='" + (n == 0 ? "before" : "other" + n) + "'/></bean></beans>";
    }
    private static final String APP = """
            package app;
            import java.nio.file.*;
            import org.springframework.context.support.GenericApplicationContext;
            import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
            import org.springframework.beans.factory.support.AbstractBeanDefinition;
            import org.springframework.beans.factory.config.TypedStringValue;
            import org.springframework.core.io.FileSystemResource;
            public class App {
                public static class Target {
                    static int created; public String label;
                    public Target() { created++; }
                    public void setLabel(String value) { label = value; }
                }
                public static class Other { public String label; public void setLabel(String value) { label = value; } }
                static String label(AbstractBeanDefinition definition) {
                    return ((TypedStringValue) definition.getPropertyValues().get("label")).getValue();
                }
                public static void main(String[] args) throws Exception {
                    var context = new GenericApplicationContext(); context.setClassLoader(App.class.getClassLoader());
                    context.getBeanFactory().setBeanClassLoader(App.class.getClassLoader());
                    var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false); reader.setBeanClassLoader(App.class.getClassLoader());
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs"))); context.refresh();
                    var factory = context.getBeanFactory(); Target held = context.getBean("warm", Target.class); Other other = context.getBean("other", Other.class);
                    var warm = (AbstractBeanDefinition) factory.getBeanDefinition("warm"); var cold = (AbstractBeanDefinition) factory.getBeanDefinition("cold");
                    Object warmMerged = factory.getMergedBeanDefinition("warm"), coldMerged = factory.getMergedBeanDefinition("cold");
                    System.out.println("READY=" + (Target.created == 1 && !factory.containsSingleton("cold") && !warm.isLazyInit() && cold.isLazyInit()));
                    Path dir = Path.of(System.getProperty("test.dir"));
                    for (int n = 1; n <= 6; n++) {
                        while (!Files.exists(dir.resolve("probe" + n))) Thread.sleep(20);
                        boolean ids = held == context.getBean("warm") && other == context.getBean("other")
                            && warm == factory.getBeanDefinition("warm") && cold == factory.getBeanDefinition("cold");
                        var wm = (AbstractBeanDefinition) factory.getMergedBeanDefinition("warm"); var cm = (AbstractBeanDefinition) factory.getMergedBeanDefinition("cold");
                        boolean metadata = !warm.isLazyInit() && cold.isLazyInit() && !wm.isLazyInit() && cm.isLazyInit()
                            && (n >= 4 || (warmMerged == wm && coldMerged == cm)) && label(warm).equals(held.label)
                            // The existing warm property path mirrors raw values; only the cold path invalidates merged metadata.
                            && label(wm).equals("before") && label(cm).equals(label(cold));
                        System.out.println("PROBE" + n + "=" + ids + ":" + metadata + ":" + !factory.containsSingleton("cold")
                            + ":" + Target.created + ":" + held.label + ":" + label(cold) + ":" + other.label);
                    }
                    Target created = context.getBean("cold", Target.class);
                    System.out.println("CREATED=" + (created == context.getBean("cold")) + ":" + created.label + ":" + Target.created);
                    while (true) Thread.sleep(100);
                }
            }
            """;
}
