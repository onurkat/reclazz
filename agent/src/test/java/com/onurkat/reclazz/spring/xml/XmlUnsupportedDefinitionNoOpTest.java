/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlUnsupportedDefinitionNoOpTest {
    @TempDir Path tmp;
    public static class Settings {
        static int created;
        String label;
        Object value;
        public Settings() { created++; }
        public void setLabel(String value) { label = value; }
        public void setValue(Object value) { this.value = value; }
        public void init() { }
    }
    public static class Lifecycle extends Settings implements InitializingBean, DisposableBean {
        static int initialized, destroyed;
        public void afterPropertiesSet() { initialized++; }
        public void destroy() { destroyed++; }
    }
    public static class OrderedLifecycle extends Lifecycle {
        public void setValue(Object value) { super.setValue(value); label += ":marker"; }
    }
    public static class UnknownDefinition extends GenericBeanDefinition { }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); Settings.created = Lifecycle.initialized = Lifecycle.destroyed = 0; }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @ParameterizedTest @ValueSource(strings = {"abstract", "prototype", "lifecycle"})
    void unchangedUnsupportedDefinitionsStayQuietAndDoNotMutate(String kind) throws Exception {
        try (var s = new Scope(kind)) {
            Object raw = s.raw(), merged = s.context.getBeanFactory().getMergedBeanDefinition("special");
            Object payload = s.raw().getPropertyValues().get("label");
            int created = Settings.created, initialized = Lifecycle.initialized;
            for (int i = 0; i < 3; i++) {
                s.reload(s.special("before"), "before");
                s.noWarning(); assertSame(raw, s.raw()); assertSame(payload, s.raw().getPropertyValues().get("label"));
                assertSame(merged, s.context.getBeanFactory().getMergedBeanDefinition("special"));
                assertEquals(created, Settings.created); assertEquals(initialized, Lifecycle.initialized); assertEquals(0, Lifecycle.destroyed);
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"abstract", "prototype", "lifecycle"})
    void unchangedUnsupportedSiblingAllowsOrdinaryUpdate(String kind) throws Exception {
        try (var s = new Scope(kind)) {
            Object raw = s.raw(); int created = Settings.created;
            s.reload(s.special("before"), "after");
            assertSame(raw, s.raw()); assertEquals("after", s.other.label); assertEquals(created, Settings.created); s.noWarning();
        }
    }
    @ParameterizedTest @ValueSource(strings = {"abstract", "prototype", "lifecycle"})
    void changedUnsupportedStillWarnsAndPreservesRawAndLiveState(String kind) throws Exception {
        try (var s = new Scope(kind)) {
            Object raw = s.raw(); int created = Settings.created;
            Settings held = kind.equals("lifecycle") ? s.context.getBean("special", Settings.class) : null;
            for (int i = 0; i < 2; i++) {
                s.reload(s.special("rejected"), "after" + i); s.warning("special");
                assertSame(raw, s.raw()); assertEquals("before", ((TypedStringValue) s.raw().getPropertyValues().get("label")).getValue());
                assertEquals("after" + i, s.other.label); assertEquals(created, Settings.created);
                if (held != null) { assertSame(held, s.context.getBean("special")); assertEquals("before", held.label); }
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"abstract", "prototype", "lifecycle"})
    void newlyAddedUnsupportedDefinitionStillWarns(String kind) throws Exception {
        try (var s = new Scope(kind)) {
            s.reload(s.special("before") + s.special("before").replace("id='special'", "id='added'"), "after");
            assertFalse(s.context.containsBeanDefinition("added")); s.warning("added");
        }
    }
    @ParameterizedTest @ValueSource(strings = {"abstract", "prototype", "lifecycle"})
    void removedUnsupportedDefinitionStillWarnsAndRemainsLive(String kind) throws Exception {
        try (var s = new Scope(kind)) {
            Object raw = s.raw(); int created = Settings.created;
            s.reload("", "after"); s.warning("special"); assertSame(raw, s.raw());
            assertEquals(created, Settings.created); assertEquals(0, Lifecycle.destroyed);
        }
    }
    @Test void sourceClassResolutionAndExplicitSingletonNormalizationDoNotWarn() throws Exception {
        try (var s = new Scope("abstract")) {
            s.raw().setBeanClass(Settings.class); s.raw().setSource("source marker"); s.raw().setResourceDescription("different source marker");
            Object raw = s.raw();
            s.reload(s.special("before").replace("abstract='true'", "abstract='true' scope='singleton'"), "after");
            assertSame(raw, s.raw()); s.noWarning();
        }
    }
    @Test void unsupportedMetadataChangeIsNotExempted() throws Exception {
        try (var s = new Scope("abstract")) {
            Object raw = s.raw();
            s.reload(s.special("before").replace("abstract='true'", "abstract='true' primary='true'"), "after");
            assertSame(raw, s.raw()); assertFalse(s.raw().isPrimary()); s.warning("special");
        }
    }
    @Test void richPrototypeConstructorDefinitionNoOpIsQuietButEditStillWarns() throws Exception {
        try (var s = new Scope("prototype")) {
            s.extra = "<bean id='rich' class='java.lang.String' scope='prototype'><constructor-arg value='old'/></bean>";
            // Native fixture registration supplies an existing resource-owned rich prototype.
            var reader = new XmlBeanDefinitionReader(s.context); reader.setValidating(false);
            Files.writeString(s.xml, "<beans xmlns='http://www.springframework.org/schema/beans'>" + s.extra + "</beans>");
            reader.loadBeanDefinitions(new FileSystemResource(s.xml.toFile()));
            Object raw = s.context.getBeanFactory().getBeanDefinition("rich");
            s.reload(s.special("before"), "after"); s.noWarning(); assertSame(raw, s.context.getBeanFactory().getBeanDefinition("rich"));
            s.extra = s.extra.replace("value='old'", "value='new'");
            s.reload(s.special("before"), "after"); s.warning("rich"); assertSame(raw, s.context.getBeanFactory().getBeanDefinition("rich"));
        }
    }
    @Test void customDefinitionAndSupplierRemainUnverifiedAndWarn() throws Exception {
        try (var s = new Scope("abstract")) {
            var custom = new UnknownDefinition(); custom.setBeanClassName(Settings.class.getName()); custom.setAbstract(true);
            custom.getPropertyValues().add("label", new TypedStringValue("before"));
            s.context.registerBeanDefinition("special", custom);
            s.reload(s.special("before"), "after"); s.warning("special"); assertSame(custom, s.raw());
        }
        try (var s = new Scope("prototype")) {
            s.raw().setInstanceSupplier(Settings::new); Object raw = s.raw();
            s.reload(s.special("before"), "after"); s.warning("special"); assertSame(raw, s.raw());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"subclass", "supplier"})
    void nestedUnverifiedConstructionCannotBeSilenced(String kind) throws Exception {
        try (var s = new Scope("abstract")) {
            GenericBeanDefinition inner = kind.equals("subclass") ? new UnknownDefinition() : new GenericBeanDefinition();
            inner.setBeanClassName(Settings.class.getName()); inner.setLazyInit(false);
            inner.getPropertyValues().add("label", new TypedStringValue("inner"));
            if (kind.equals("supplier")) inner.setInstanceSupplier(Settings::new);
            s.raw().getPropertyValues().add("value", new org.springframework.beans.factory.config.BeanDefinitionHolder(inner, "inner"));
            String body = s.special("before").replace("</bean>", "<property name='value'><bean class='" + Settings.class.getName()
                    + "'><property name='label' value='inner'/></bean></property></bean>");
            Object raw = s.raw(); s.reload(body, "after"); s.warning("special"); assertSame(raw, s.raw());
        }
    }
    @Test void comparisonBoundFailureDoesNotSilenceUnsupportedWarning() throws Exception {
        try (var s = new Scope("abstract")) {
            String deep = "<value>deep</value>"; for (int i = 0; i < 36; i++) deep = "<list>" + deep + "</list>";
            String body = s.special("before").replace("</bean>", "<property name='value'>" + deep + "</property></bean>");
            var reader = new XmlBeanDefinitionReader(s.context); reader.setValidating(false);
            Files.writeString(s.xml, "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>"); reader.loadBeanDefinitions(new FileSystemResource(s.xml.toFile()));
            Object raw = s.raw(); s.reload(body, "after"); s.warning("special"); assertSame(raw, s.raw());
        }
    }
    @Test void setterDeclarationOrderIsNotMistakenForNoOp() throws Exception {
        try (var s = new Scope("abstract")) {
            String before = "<bean id='ordered' class='" + OrderedLifecycle.class.getName()
                    + "'><property name='label' value='base'/><property name='value' value='marker'/></bean>";
            String after = "<bean id='ordered' class='" + OrderedLifecycle.class.getName()
                    + "'><property name='value' value='marker'/><property name='label' value='base'/></bean>";
            var reader = new XmlBeanDefinitionReader(s.context); reader.setValidating(false);
            Files.writeString(s.xml, "<beans xmlns='http://www.springframework.org/schema/beans'>" + before + "</beans>");
            reader.loadBeanDefinitions(new FileSystemResource(s.xml.toFile()));
            OrderedLifecycle held = s.context.getBean("ordered", OrderedLifecycle.class);
            assertEquals("base:marker", held.label);
            s.extra = after; s.reload(s.special("before"), "after"); s.warning("ordered");
            assertSame(held, s.context.getBean("ordered")); assertEquals("base:marker", held.label);
            // Fresh native creation proves this ordering edit changes behavior.
            try (var fresh = new GenericApplicationContext()) {
                var freshReader = new XmlBeanDefinitionReader(fresh); freshReader.setValidating(false);
                Files.writeString(s.xml, "<beans xmlns='http://www.springframework.org/schema/beans'>" + after + "</beans>");
                freshReader.loadBeanDefinitions(new FileSystemResource(s.xml.toFile())); fresh.refresh();
                assertEquals("base", fresh.getBean("ordered", OrderedLifecycle.class).label);
            }
        }
    }
    @Test void aliasChangesStillWarnOnUnchangedRichUnsupportedDefinition() throws Exception {
        try (var s = new Scope("prototype")) {
            s.extra = "<bean id='rich' name='alias' class='java.lang.String' scope='prototype'><constructor-arg value='old'/></bean>";
            var reader = new XmlBeanDefinitionReader(s.context); reader.setValidating(false);
            Files.writeString(s.xml, "<beans xmlns='http://www.springframework.org/schema/beans'>" + s.extra + "</beans>"); reader.loadBeanDefinitions(new FileSystemResource(s.xml.toFile()));
            s.extra = s.extra.replace(" name='alias'", ""); s.reload(s.special("before"), "after");
            s.warning("rich"); assertTrue(s.context.isAlias("alias"));
        }
    }
    private final class Scope implements AutoCloseable {
        final Path xml;
        final String kind;
        final GenericApplicationContext context = new GenericApplicationContext();
        final SpringXmlReloader reloader;
        final Settings other;
        final List<String> events = new ArrayList<>();
        String extra = "";
        Scope(String kind) throws Exception {
            this.kind = kind; xml = Files.createTempFile(tmp, "unsupported-", ".xml");
            Files.writeString(xml, document(special("before"), "before"));
            var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
            reader.loadBeanDefinitions(new FileSystemResource(xml.toFile())); context.refresh(); other = context.getBean("other", Settings.class);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (proxy, method, args) -> switch (method.getName()) { case "getApplicationContext" -> context; default -> throw new UnsupportedOperationException(method.getName()); });
            reloader = new SpringXmlReloader(platform);
        }
        String special(String label) {
            Class<?> type = kind.equals("lifecycle") ? Lifecycle.class : Settings.class;
            String attributes = kind.equals("abstract") ? "abstract='true'" : kind.equals("prototype") ? "scope='prototype'" : "";
            return "<bean id='special' class='" + type.getName() + "' " + attributes + "><property name='label' value='" + label + "'/></bean>";
        }
        String document(String special, String label) {
            return "<beans xmlns='http://www.springframework.org/schema/beans'>" + special
                    + "<bean id='other' class='" + Settings.class.getName() + "'><property name='label' value='" + label + "'/></bean>" + extra + "</beans>";
        }
        AbstractBeanDefinition raw() { return (AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition("special"); }
        void reload(String body, String label) throws Exception {
            Files.writeString(xml, document(body, label)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message); StatusReporter.addListener(listener);
            try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(other, context.getBean("other"));
        }
        void noWarning() { assertFalse(events.stream().anyMatch(v -> v.contains("Restart required")), events.toString()); assertEquals(List.of("Nothing from this session needs a restart."), RestartLedger.digest()); }
        void warning(String name) { assertTrue(events.stream().anyMatch(v -> v.contains("Restart required") && v.contains(name)), events.toString()); assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains(name)), RestartLedger.digest().toString()); }
        public void close() { context.close(); }
    }
}
