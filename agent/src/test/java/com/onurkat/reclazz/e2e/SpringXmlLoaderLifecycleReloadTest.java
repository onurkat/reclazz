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

class SpringXmlLoaderLifecycleReloadTest {
    @TempDir Path tmp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lifecycleEditsRefuseInNormalAndChildLoadersWhileSiblingsReload(boolean child) throws Exception {
        Path xml = tmp.resolve("lifecycle.xml"); Files.writeString(xml, document("before", "before"));
        var builder = WatchedApp.in(tmp).classpath(WatchedApp.springClasspath());
        if (child) builder.childClassLoader();
        try (var app = builder.jvmArgs("-Ddefs=" + xml, "-Dtest.dir=" + tmp).with("App", APP).start()) {
            app.awaitOrFail("READY=true", "lifecycle fixture missing"); app.awaitOrFail("] Watching ", "watcher missing");
            if (child) app.awaitOrFail("APP_IN_CHILD_MODULE=true", "child fixture missing");
            save(app, xml, "before", "before", 1); probe(app, 1, "before");
            assertEquals(0, warnings(app), app.tail());
            for (int n = 2; n <= 3; n++) {
                save(app, xml, "rejected" + n, "other" + n, n); probe(app, n, "other" + n);
                assertEquals(5 * (n - 1), warnings(app), app.tail());
                assertEquals(n - 1, warningHeaders(app), app.tail());
                assertTrue(app.output().stream().anyMatch(v -> v.contains("1 change applied, 5 require restart")), app.tail());
            }
            save(app, xml, "before", "final", 4); probe(app, 4, "final");
            assertEquals(10, warnings(app), app.tail());
            assertEquals(2, warningHeaders(app), app.tail());
            Files.createFile(tmp.resolve("finish")); app.awaitOrFail("COLD=before:before", "cold creation must retain original state");
            assertFalse(app.output().stream().anyMatch(v -> v.contains("Spring property updated: init.")
                    || v.contains("Spring property updated: disposable.") || v.contains("Spring property updated: bpp.")
                    || v.contains("Spring property updated: bfpp.") || v.contains("Spring property updated: cold.")), app.tail());
        }
    }
    private void save(WatchedApp app, Path xml, String value, String other, int count) throws Exception {
        Files.writeString(xml, document(value, other));
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until && reports(app) < count) Thread.sleep(25);
        assertEquals(count, reports(app), app.tail());
    }
    private long reports(WatchedApp app) { return app.output().stream().filter(v -> v.contains("Spring XML lifecycle.xml")
            && (v.contains("reloaded:") || v.contains("parsed OK"))).count(); }
    private long warnings(WatchedApp app) { return app.output().stream().filter(v -> v.contains("lifecycle/infrastructure state was not refreshed")).count(); }
    private long warningHeaders(WatchedApp app) { return app.output().stream().filter(v -> v.contains("Restart required")).count(); }
    private void probe(WatchedApp app, int n, String other) throws Exception {
        Files.createFile(tmp.resolve("probe" + n)); app.awaitOrFail("PROBE" + n + "=", "probe missing");
        String line = app.latest("PROBE" + n + "=");
        assertEquals("PROBE" + n + "=true:true:true:false:0:before:before:" + other + ":isolated",
                line.substring(line.indexOf("PROBE")), app.tail());
    }
    private static String document(String value, String other) {
        StringBuilder xml = new StringBuilder("<beans xmlns='http://www.springframework.org/schema/beans'>");
        for (String[] entry : new String[][]{{"init", "Init"}, {"disposable", "Dispose"}, {"bpp", "Bpp"}, {"bfpp", "Bfpp"}})
            xml.append("<bean id='").append(entry[0]).append("' class='app.App$").append(entry[1])
                    .append("'><property name='label' value='").append(value).append("'/></bean>");
        return xml + "<bean id='template' abstract='true' class='app.App$Cold'/>"
                + "<bean id='cold' parent='template' lazy-init='true'><property name='label' value='" + value + "'/></bean>"
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
            static int coldInitializations;
            public static class Settings {
                public String label; public int writes, callbacks;
                public void setLabel(String value) { writes++; label = value; }
                public String state() { return label + ":" + writes + ":" + callbacks; }
            }
            public static class Init extends Settings implements org.springframework.beans.factory.InitializingBean {
                public String snapshot; public void afterPropertiesSet() { callbacks++; snapshot = label; }
            }
            public static class Dispose extends Settings implements org.springframework.beans.factory.DisposableBean {
                public void destroy() { callbacks++; }
            }
            public static class Bpp extends Settings implements org.springframework.beans.factory.config.BeanPostProcessor { }
            public static class Bfpp extends Settings implements org.springframework.beans.factory.config.BeanFactoryPostProcessor {
                public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) { callbacks++; }
            }
            public static class Cold extends Init { static { coldInitializations++; } }
            public static void main(String[] args) throws Exception {
                // The factory owns the application loader; the watcher/TCCL need not see its classes.
                Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
                try (var context = new GenericApplicationContext(); var isolated = new GenericApplicationContext()) {
                    context.setClassLoader(App.class.getClassLoader());
                    context.getBeanFactory().setBeanClassLoader(App.class.getClassLoader());
                    var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
                    reader.setBeanClassLoader(App.class.getClassLoader());
                    reader.loadBeanDefinitions(new FileSystemResource(System.getProperty("defs"))); context.refresh();
                    isolated.registerBean("isolated", Settings.class, () -> { var s = new Settings(); s.label = "isolated"; return s; });
                    isolated.refresh(); Settings untouched = isolated.getBean("isolated", Settings.class);
                    var factory = context.getBeanFactory(); Settings other = context.getBean("other", Settings.class);
                    String[] names = {"init", "disposable", "bpp", "bfpp", "cold"};
                    Object[] raw = new Object[5], merged = new Object[5], instances = new Object[4]; String[] states = new String[4];
                    for (int i = 0; i < 5; i++) {
                        raw[i] = factory.getBeanDefinition(names[i]); merged[i] = factory.getMergedBeanDefinition(names[i]);
                        if (i < 4) { instances[i] = context.getBean(names[i]); states[i] = ((Settings)instances[i]).state(); }
                    }
                    Init init = (Init) instances[0];
                    System.out.println("READY=" + ("before".equals(init.snapshot) && coldInitializations == 0 && !factory.containsSingleton("cold")));
                    Path dir = Path.of(System.getProperty("test.dir"));
                    for (int n = 1; n <= 4; n++) {
                        while (!Files.exists(dir.resolve("probe" + n))) Thread.sleep(20);
                        boolean definitions = true, identities = other == context.getBean("other"), unchanged = true;
                        for (int i = 0; i < 5; i++) {
                            definitions &= raw[i] == factory.getBeanDefinition(names[i]) && merged[i] == factory.getMergedBeanDefinition(names[i]);
                            definitions &= "before".equals(((TypedStringValue)((AbstractBeanDefinition)raw[i]).getPropertyValues().get("label")).getValue());
                            definitions &= "before".equals(((TypedStringValue)((AbstractBeanDefinition)merged[i]).getPropertyValues().get("label")).getValue());
                            if (i < 4) { identities &= instances[i] == context.getBean(names[i]); unchanged &= states[i].equals(((Settings) instances[i]).state()); }
                        }
                        identities &= untouched == isolated.getBean("isolated");
                        System.out.println("PROBE" + n + "=" + definitions + ":" + identities + ":" + unchanged + ":" + factory.containsSingleton("cold")
                            + ":" + coldInitializations + ":" + init.label + ":" + init.snapshot + ":" + other.label + ":" + untouched.label);
                    }
                    while (!Files.exists(dir.resolve("finish"))) Thread.sleep(20);
                    Init cold = context.getBean("cold", Init.class); System.out.println("COLD=" + cold.label + ":" + cold.snapshot);
                    while (true) Thread.sleep(100);
                }
            }
        }
        """;
}
