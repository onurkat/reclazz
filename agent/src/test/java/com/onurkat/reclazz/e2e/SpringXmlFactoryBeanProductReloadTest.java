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

class SpringXmlFactoryBeanProductReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void factoryEditsWarnBeforeStateChangesAndNoOpStaysQuiet(boolean child) throws Exception {
        Path xml = tmp.resolve("factory.xml"); Files.writeString(xml, document("before", "before"));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + xml, "-Dtest.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY=true", "native factory/product fixture missing");
            app.awaitOrFail("] Watching ", "watcher missing");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child module missing");
            save(app, xml, "before", "before", 1); probe(app, 1, "before");
            assertEquals(0, warnings(app), app.tail());
            for (int n = 2; n <= 4; n++) {
                save(app, xml, "rejected" + n, "other" + n, n); probe(app, n, "other" + n);
                assertEquals(3 * (n - 1), warnings(app), app.tail());
                assertTrue(app.output().stream().anyMatch(v -> v.contains("1 change applied, 3 require restart")), app.tail());
            }
            save(app, xml, "before", "final", 5); probe(app, 5, "final");
            assertEquals(9, warnings(app), app.tail());
            assertFalse(app.output().stream().anyMatch(v -> v.contains("Spring property updated: warm.")
                    || v.contains("Spring property updated: cold.") || v.contains("Spring property updated: fresh.")), app.tail());
            Files.createFile(tmp.resolve("finish"));
            app.awaitOrFail("COLD=before", "deferred creation must retain original factory definition");
        }
    }
    private void save(WatchedApp app, Path xml, String value, String other, int count) throws Exception {
        Files.writeString(xml, document(value, other));
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && reports(app) < count) Thread.sleep(25);
        assertEquals(count, reports(app), app.tail());
    }
    private long reports(WatchedApp app) { return app.output().stream().filter(v -> v.contains("Spring XML factory.xml")
            && (v.contains("reloaded:") || v.contains("parsed OK"))).count(); }
    private long warnings(WatchedApp app) { return app.output().stream().filter(v -> v.contains("FactoryBean definition changed")).count(); }
    private void probe(WatchedApp app, int n, String other) throws Exception {
        Files.createFile(tmp.resolve("probe" + n)); app.awaitOrFail("PROBE" + n + "=", "factory probe missing");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=true:true:before:before:before:false:" + other,
                line.substring(line.indexOf("PROBE")), app.tail());
    }
    private static String document(String value, String other) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<bean id='warm' class='app.App$Factory'><property name='label' value='" + value + "'/></bean>"
                + "<bean id='fresh' class='app.App$Factory'><property name='label' value='" + value
                + "'/><property name='productSingleton' value='false'/></bean>"
                + "<bean id='cold' class='app.App$Factory' lazy-init='true'><property name='label' value='" + value + "'/></bean>"
                + "<bean id='stable' class='app.App$Factory'><property name='label' value='stable'/></bean>"
                + "<bean id='other' class='app.App$Settings'><property name='label' value='" + other + "'/></bean></beans>";
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
                public record Product(String label) { }
                public static class Factory implements org.springframework.beans.factory.FactoryBean<Product> {
                    public String label;
                    public boolean productSingleton = true;
                    public void setLabel(String value) { label = value; }
                    public void setProductSingleton(boolean value) { productSingleton = value; }
                    public Product getObject() { return new Product(label); }
                    public Class<?> getObjectType() { return Product.class; }
                    public boolean isSingleton() { return productSingleton; }
                }
                public static class Settings { public String label; public void setLabel(String value) { label = value; } }
                public static void main(String[] args) throws Exception {
                    try (var context = new GenericApplicationContext()) {
                        var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
                        reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs"))); context.refresh();
                        var factory = context.getBeanFactory();
                        Factory warm = context.getBean("&warm", Factory.class), fresh = context.getBean("&fresh", Factory.class);
                        Product product = context.getBean("warm", Product.class);
                        Object stable = context.getBean("stable"), stableFactory = context.getBean("&stable");
                        Settings other = context.getBean("other", Settings.class);
                        var raw = new java.util.LinkedHashMap<String,Object>(); var merged = new java.util.LinkedHashMap<String,Object>();
                        for (String name : new String[]{"warm","fresh","cold"}) {
                            raw.put(name, factory.getBeanDefinition(name)); merged.put(name, factory.getMergedBeanDefinition(name));
                        }
                        System.out.println("READY=" + (product == context.getBean("warm") && warm.getObject() != warm.getObject()
                            && context.getBean("fresh") != context.getBean("fresh") && !factory.containsSingleton("cold")));
                        Path dir = Path.of(System.getProperty("test.dir"));
                        for (int n = 1; n <= 5; n++) {
                            while (!Files.exists(dir.resolve("probe" + n))) Thread.sleep(20);
                            boolean definitions = true;
                            for (String name : raw.keySet()) {
                                definitions &= raw.get(name) == factory.getBeanDefinition(name) && merged.get(name) == factory.getMergedBeanDefinition(name);
                                definitions &= "before".equals(((TypedStringValue)((AbstractBeanDefinition)raw.get(name)).getPropertyValues().get("label")).getValue());
                                definitions &= "before".equals(((TypedStringValue)((AbstractBeanDefinition)merged.get(name)).getPropertyValues().get("label")).getValue());
                            }
                            System.out.println("PROBE" + n + "=" + (warm == context.getBean("&warm") && fresh == context.getBean("&fresh")
                                && product == context.getBean("warm") && stable == context.getBean("stable")
                                && stableFactory == context.getBean("&stable") && other == context.getBean("other"))
                                + ":" + definitions + ":" + warm.label + ":" + product.label() + ":"
                                + context.getBean("fresh", Product.class).label() + ":" + factory.containsSingleton("cold") + ":" + other.label);
                        }
                        while (!Files.exists(dir.resolve("finish"))) Thread.sleep(20);
                        System.out.println("COLD=" + context.getBean("cold", Product.class).label());
                    }
                }
            }
            """;
}
