/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlLoaderLifecycleDiagnosticsTest {
    @TempDir Path tmp;
    public static class Settings {
        String label;
        public void setLabel(String value) { label = value; }
    }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear();
        for (String type : List.of("Init", "Dispose", "Bpp", "Bfpp", "Ordinary")) System.clearProperty("com.example.fixture." + type); }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @ParameterizedTest @ValueSource(strings = {"Init", "Dispose", "Bpp", "Bfpp"})
    void childOnlyLifecycleRefusesRepeatedEditsWhileSiblingUpdates(String type) throws Exception {
        try (var s = new Scope(type, false, false)) {
            Object bean = s.context.getBeanFactory().getBean("target"), raw = s.raw(), merged = s.merged();
            assertThrows(ClassNotFoundException.class, () -> Class.forName(s.typeName(), false,
                    Thread.currentThread().getContextClassLoader()));
            assertSame(s.loader, bean.getClass().getClassLoader());
            String state = (String) bean.getClass().getMethod("state").invoke(bean);
            for (int n = 1; n <= 3; n++) {
                s.reload("after" + n, "other" + n); s.warning(); s.preserved(raw, merged);
                assertSame(bean, s.context.getBeanFactory().getBean("target"));
                assertEquals(state, bean.getClass().getMethod("state").invoke(bean), "no setters or lifecycle callbacks");
                assertEquals("other" + n, s.settings.label);
            }
            s.reload("before", "final"); s.quiet(); s.preserved(raw, merged);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"Init", "Dispose", "Bpp", "Bfpp"})
    void coldInheritedLifecycleStaysUninitializedAndUncreated(String type) throws Exception {
        try (var s = new Scope(type, true, true)) {
            Object raw = s.raw(), merged = s.merged();
            assertNull(System.getProperty("com.example.fixture." + type));
            s.reload("after", "other"); s.warning(); s.preserved(raw, merged);
            assertNull(System.getProperty("com.example.fixture." + type), "type inspection must not initialize the class");
            assertFalse(s.context.getBeanFactory().containsSingleton("target"));
            assertEquals("other", s.settings.label);
            s.reload("before", "final"); s.quiet(); s.preserved(raw, merged);
        }
    }
    @Test void identicalClassNamesInDifferentLoadersDoNotShareSafetyDecisions() throws Exception {
        try (var lifecycle = new Scope("Init", false, false);
             var ordinary = new Scope("Init", false, false, false)) {
            Object held = lifecycle.context.getBean("target"), otherHeld = ordinary.context.getBean("target");
            assertNotSame(held.getClass(), otherHeld.getClass());
            lifecycle.reload("rejected", "lifeSibling"); lifecycle.warning();
            ordinary.reload("accepted", "plainSibling"); ordinary.quiet();
            assertEquals("before", held.getClass().getMethod("label").invoke(held));
            assertEquals("accepted", otherHeld.getClass().getMethod("label").invoke(otherHeld));
        }
    }
    @Test void resolvedDeclaredClassKeepsItsIdentityWhenFactoryLoaderHasADifferentClassWithTheSameName() throws Exception {
        try (var lifecycle = new Scope("Init", false, false);
             var ordinary = new Scope("Init", false, false, false)) {
            Class<?> declared = lifecycle.context.getBeanFactory().getBean("target").getClass();
            assertNotSame(declared, Class.forName(declared.getName(), false, ordinary.loader));
            var live = new DefaultListableBeanFactory(); live.setBeanClassLoader(ordinary.loader);
            var parsed = new DefaultListableBeanFactory(); parsed.setBeanClassLoader(ordinary.loader);
            var before = new org.springframework.beans.factory.support.GenericBeanDefinition();
            before.setBeanClass(declared); before.getPropertyValues().add("label", "before");
            var next = before.cloneBeanDefinition(); next.getPropertyValues().add("label", "after");
            live.registerBeanDefinition("target", before); parsed.registerBeanDefinition("target", next);
            Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{View.class},
                    (instance, method, args) -> { throw new AssertionError("no proxy methods during preflight"); });
            live.registerSingleton("target", proxy);
            var merged = (AbstractBeanDefinition) live.getMergedBeanDefinition("target");
            assertTrue(merged.hasBeanClass()); assertSame(declared, merged.getBeanClass());
            var diff = new BeanDefinitionDiff(); XmlSafetyClassifier.classify(live, parsed, tmp.resolve("resolved.xml"), diff);
            assertEquals(1, diff.unsafe.size()); assertTrue(diff.unsafe.get(0).reason.contains("InitializingBean"));
            assertTrue(diff.propertyChanges.isEmpty()); assertSame(proxy, live.getSingleton("target"));
            assertSame(merged, live.getMergedBeanDefinition("target")); assertSame(declared, merged.getBeanClass());
        }
    }
    @Test void newChildLifecycleDefinitionIsNotInstantiated() throws Exception {
        try (var s = new Scope("Ordinary", false, false)) {
            s.extra = "<bean id='newLifecycle' class='com.example.Fixture$Init'><property name='label' value='new'/></bean>";
            s.reload("before", "other");
            assertTrue(s.events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("newLifecycle")), s.events.toString());
            assertFalse(s.context.getBeanFactory().containsBeanDefinition("newLifecycle"));
            assertNull(System.getProperty("com.example.fixture.Init"));
        }
    }
    public interface View { String label(); }
    public static class InitializingSettings extends Settings implements org.springframework.beans.factory.InitializingBean {
        public void afterPropertiesSet() { }
    }
    @Test void proxyWithoutLifecycleInterfaceStillUsesDeclaredLifecycleType() {
        var live = new DefaultListableBeanFactory(); var parsed = new DefaultListableBeanFactory();
        var before = new org.springframework.beans.factory.support.GenericBeanDefinition();
        before.setBeanClass(InitializingSettings.class); before.getPropertyValues().add("label", "before");
        var next = before.cloneBeanDefinition(); next.getPropertyValues().add("label", "after");
        live.registerBeanDefinition("target", before); parsed.registerBeanDefinition("target", next);
        Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{View.class},
                (instance, method, args) -> { throw new AssertionError("classification must not call proxy methods"); });
        live.registerSingleton("target", proxy);
        var diff = new BeanDefinitionDiff(); XmlSafetyClassifier.classify(live, parsed, tmp.resolve("proxy.xml"), diff);
        assertEquals(1, diff.unsafe.size()); assertTrue(diff.unsafe.get(0).reason.contains("InitializingBean"));
        assertTrue(diff.propertyChanges.isEmpty()); assertSame(proxy, live.getSingleton("target"));
        assertSame(before, live.getBeanDefinition("target"));
    }
    @Test void unavailableExistingTypeRefusesBeforePropertyMutation() throws Exception {
        var live = new DefaultListableBeanFactory(); var parsed = new DefaultListableBeanFactory();
        var before = new org.springframework.beans.factory.support.GenericBeanDefinition();
        before.setBeanClassName("com.example.Unavailable"); before.getPropertyValues().add("label", "before");
        var next = before.cloneBeanDefinition(); next.getPropertyValues().add("label", "after");
        live.registerBeanDefinition("target", before); parsed.registerBeanDefinition("target", next);
        var diff = new BeanDefinitionDiff(); XmlSafetyClassifier.classify(live, parsed, tmp.resolve("missing.xml"), diff);
        assertEquals(1, diff.unsafe.size()); assertTrue(diff.propertyChanges.isEmpty()); assertTrue(diff.recreated.isEmpty());
        assertSame(before, live.getBeanDefinition("target"));
    }
    @Test void ordinaryBootstrapTypeAndNewOrdinaryBeanRemainSupported() {
        var live = new DefaultListableBeanFactory(); var parsed = new DefaultListableBeanFactory();
        var old = new org.springframework.beans.factory.support.GenericBeanDefinition(); old.setBeanClass(Object.class);
        live.registerBeanDefinition("object", old); parsed.registerBeanDefinition("object", old.cloneBeanDefinition());
        var added = new org.springframework.beans.factory.support.GenericBeanDefinition(); added.setBeanClass(Settings.class);
        parsed.registerBeanDefinition("added", added);
        var diff = new BeanDefinitionDiff(); XmlSafetyClassifier.classify(live, parsed, tmp.resolve("ordinary.xml"), diff);
        assertTrue(diff.unsafe.isEmpty()); assertEquals(1, diff.added.size()); assertTrue(diff.propertyChanges.isEmpty());
    }

    private final class Scope implements AutoCloseable {
        final String type;
        final boolean cold, inherited;
        final Path xml;
        final URLClassLoader loader;
        final GenericApplicationContext context;
        final Settings settings;
        final SpringXmlReloader reloader;
        final List<String> events = new ArrayList<>();
        String extra = "";
        Scope(String type, boolean cold, boolean inherited) throws Exception { this(type, cold, inherited, true); }
        Scope(String type, boolean cold, boolean inherited, boolean lifecycle) throws Exception {
            this.type = type; this.cold = cold; this.inherited = inherited;
            Path dir = Files.createTempDirectory(tmp, "fixture-");
            Path source = dir.resolve("Fixture.java"), classes = Files.createDirectory(dir.resolve("classes"));
            Files.writeString(source, lifecycle ? FIXTURE : FIXTURE.replace("implements org.springframework.beans.factory.InitializingBean", ""));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-cp",
                    WatchedApp.springClasspath(), "-d", classes.toString(), source.toString()));
            loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
            xml = dir.resolve("lifecycle.xml"); Files.writeString(xml, document("before", "before"));
            context = new GenericApplicationContext(); context.setClassLoader(loader);
            context.getBeanFactory().setBeanClassLoader(loader);
            var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false); reader.setBeanClassLoader(loader);
            var resource = new FileSystemResource(xml); reader.loadBeanDefinitions(resource); SpringXmlResources.record(reader, resource);
            // Deliberately avoid context.refresh(): Spring eagerly instantiates postprocessors,
            // defeating the cold type-resolution oracle. Native factory lifecycle still runs on getBean.
            settings = context.getBeanFactory().getBean("settings", Settings.class);
            if (!cold) context.getBeanFactory().getBean("target");
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (proxy, method, args) -> { if (method.getName().equals("getApplicationContext")) return context;
                        throw new UnsupportedOperationException(method.getName()); });
            reloader = new SpringXmlReloader(platform);
            // A cold factory is a metadata preflight fixture, not a refreshed context:
            // refresh eagerly discovers postprocessors regardless of lazy-init.
            if (!cold) context.refresh();
        }
        String typeName() { return "com.example.Fixture$" + type; }
        String document(String label, String other) {
            return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + (inherited ? "<bean id='template' abstract='true' class='" + typeName() + "'/>" : "")
                    + "<bean id='target' " + (inherited ? "parent='template'" : "class='" + typeName() + "'")
                    + " lazy-init='" + cold + "'><property name='label' value='" + label + "'/></bean>"
                    + "<bean id='settings' class='" + Settings.class.getName() + "'><property name='label' value='" + other + "'/></bean>" + extra + "</beans>";
        }
        AbstractBeanDefinition raw() { return (AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition("target"); }
        Object merged() { return context.getBeanFactory().getMergedBeanDefinition("target"); }
        void reload(String label, String other) throws Exception {
            Files.writeString(xml, document(label, other)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener); try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(settings, context.getBeanFactory().getBean("settings"));
        }
        void preserved(Object raw, Object merged) {
            assertSame(raw, raw()); assertSame(merged, merged());
            assertEquals("before", ((TypedStringValue) raw().getPropertyValues().get("label")).getValue());
            assertEquals("before", ((TypedStringValue) ((AbstractBeanDefinition) merged()).getPropertyValues().get("label")).getValue());
        }
        void warning() { assertTrue(events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("target")), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("target")), RestartLedger.digest().toString()); }
        void quiet() { assertFalse(events.stream().anyMatch(v -> v.contains("Restart required")), events.toString()); }
        public void close() throws Exception { context.close(); loader.close(); }
    }
    private static final String FIXTURE = """
        package com.example;
        public class Fixture {
            public static class Base {
                public String label, snapshot = ""; public int writes, callbacks;
                public void setLabel(String value) { writes++; label = value; }
                public String label() { return label; }
                public String state() { return label + ":" + snapshot + ":" + writes + ":" + callbacks; }
            }
            public static class Init extends Base implements org.springframework.beans.factory.InitializingBean {
                static { System.setProperty("com.example.fixture.Init", "initialized"); }
                public void afterPropertiesSet() { snapshot = label; callbacks++; }
            }
            public static class Dispose extends Base implements org.springframework.beans.factory.DisposableBean {
                static { System.setProperty("com.example.fixture.Dispose", "initialized"); }
                public void destroy() { callbacks++; }
            }
            public static class Bpp extends Base implements org.springframework.beans.factory.config.BeanPostProcessor {
                static { System.setProperty("com.example.fixture.Bpp", "initialized"); }
            }
            public static class Bfpp extends Base implements org.springframework.beans.factory.config.BeanFactoryPostProcessor {
                static { System.setProperty("com.example.fixture.Bfpp", "initialized"); }
                public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) { callbacks++; }
            }
            public static class Ordinary extends Base {
                static { System.setProperty("com.example.fixture.Ordinary", "initialized"); }
            }
        }
        """;
}
