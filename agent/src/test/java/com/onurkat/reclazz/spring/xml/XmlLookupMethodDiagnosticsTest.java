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
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class XmlLookupMethodDiagnosticsTest {
    @TempDir Path tmp;
    public static class Factory {
        String label;
        public Object select() { return "native"; }
        public Object alternate() { return "alternate"; }
        public void setLabel(String value) { label = value; }
        public void init() { }
    }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @Test void retargetWarnsAndKeepsHeldLookup() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "")) {
            s.reload(lookup("select", "b"), "before", "before");
            s.assertPreserved(); s.assertWarning();
        }
    }
    @Test void additionWarnsAndKeepsNativeMethod() throws Exception {
        try (var s = new Scope("", "")) {
            s.reload(lookup("select", "a"), "before", "before");
            assertEquals("native", s.bean().select()); s.assertPreserved(); s.assertWarning();
        }
    }
    @Test void removalWarnsAndKeepsLookup() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "")) {
            s.reload("", "before", "before");
            s.assertPreserved(); s.assertWarning();
        }
    }
    @Test void methodNameEditWarns() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "")) {
            s.reload(lookup("alternate", "a"), "before", "before");
            assertEquals("alternate", s.bean().alternate()); s.assertPreserved(); s.assertWarning();
        }
    }
    @Test void mixedEditPreservesAffectedBeanButAppliesIndependentProperty() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "")) {
            s.reload(lookup("select", "b"), "after", "after");
            assertEquals("before", s.bean().label);
            assertEquals("after", s.unrelated.label);
            assertEquals("before", ((TypedStringValue) s.definition().getPropertyValues().get("label")).getValue());
            s.assertPreserved(); s.assertWarning();
        }
    }
    @Test void repeatedRetargetKeepsWarningAndOriginalDefinition() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "")) {
            for (String name : List.of("b", "b", "c")) {
                s.reload(lookup("select", name), "after", "after");
                assertEquals("before", s.bean().label); s.assertPreserved(); s.assertWarning();
            }
        }
    }
    @Test void unchangedLookupAllowsPropertyEdit() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "")) {
            s.reload(lookup("select", "a"), "after", "after");
            assertEquals("after", s.bean().label); s.assertPreserved(); s.assertNoWarning();
        }
    }
    @Test void unchangedLookupIsNoOp() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "")) {
            s.reload(lookup("select", "a"), "before", "before");
            s.assertPreserved(); s.assertNoWarning();
        }
    }
    @Test void independentMethodReorderingDoesNotWarn() throws Exception {
        try (var s = new Scope(lookup("select", "a") + lookup("alternate", "b"), "")) {
            s.reload(lookup("alternate", "b") + lookup("select", "a"), "after", "after");
            assertSame(s.context.getBean("a"), s.bean().select());
            assertSame(s.context.getBean("b"), s.bean().alternate());
            assertEquals("after", s.bean().label); s.assertNoWarning();
        }
    }
    @Test void lazyDefinitionIsNotRewrittenOrInstantiated() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "lazy-init='true'")) {
            assertFalse(s.context.getBeanFactory().containsSingleton("factory"));
            s.reload(lookup("select", "b"), "after", "after");
            assertFalse(s.context.getBeanFactory().containsSingleton("factory"));
            assertEquals("before", s.bean().label); s.assertPreserved(); s.assertWarning();
        }
    }
    @Test void richBeanOverrideRefusalRemains() throws Exception {
        try (var s = new Scope(lookup("select", "a"), "init-method='init'")) {
            s.reload(lookup("select", "b"), "after", "after");
            assertEquals("before", s.bean().label); s.assertPreserved();
            assertTrue(s.events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("method overrides")), s.events.toString());
        }
    }
    @Test void newLookupBeanUsesNativeCreation() throws Exception {
        try (var s = new Scope("", "")) {
            s.extra = "<bean id='newFactory' class='" + Factory.class.getName() + "'>" + lookup("select", "b") + "</bean>";
            s.reload("", "before", "before");
            assertSame(s.context.getBean("b"), s.context.getBean("newFactory", Factory.class).select());
            assertFalse(s.events.stream().anyMatch(v -> v.contains("lookup-method")), s.events.toString());
        }
    }
    @Test void duplicateMethodOrderChangeIsNotMissed() throws Exception {
        try (var s = new Scope(lookup("select", "a") + lookup("select", "b"), "")) {
            assertSame(s.context.getBean("b"), s.bean().select());
            s.reload(lookup("select", "b") + lookup("select", "a"), "before", "before");
            s.assertPreserved(); s.assertWarning();
        }
    }
    private static String lookup(String name, String bean) {
        return "<lookup-method name='" + name + "' bean='" + bean + "'/>";
    }
    private final class Scope implements AutoCloseable {
        final GenericApplicationContext context = new GenericApplicationContext();
        final Path xml;
        final String attributes;
        final SpringXmlReloader reloader;
        final List<String> events = new ArrayList<>();
        final List<String> originalOverrides;
        final Factory held, unrelated;
        final Object originalResult;
        String extra = "";
        Scope(String overrides, String attributes) throws Exception {
            this.attributes = attributes;
            xml = Files.createTempFile(tmp, "lookup-", ".xml");
            Files.writeString(xml, document(overrides, "before", "before"));
            var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
            reader.loadBeanDefinitions(new FileSystemResource(xml.toFile())); context.refresh();
            held = attributes.contains("lazy-init") ? null : bean();
            originalOverrides = overrides();
            String selected = definition().getMethodOverrides().getOverrides().stream()
                    .filter(o -> o.getMethodName().equals("select"))
                    .map(o -> ((LookupOverride) o).getBeanName()).reduce((first, last) -> last).orElse(null);
            originalResult = selected == null ? "native" : context.getBean(selected);
            unrelated = context.getBean("unrelated", Factory.class);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getApplicationContext" -> context;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            reloader = new SpringXmlReloader(platform);
        }
        String document(String overrides, String label, String otherLabel) {
            return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + "<bean id='a' class='java.lang.Object'/><bean id='b' class='java.lang.Object'/><bean id='c' class='java.lang.Object'/>"
                    + "<bean id='factory' class='" + Factory.class.getName() + "' " + attributes + ">" + overrides
                    + "<property name='label' value='" + label + "'/></bean>"
                    + "<bean id='unrelated' class='" + Factory.class.getName() + "'><property name='label' value='"
                    + otherLabel + "'/></bean>" + extra + "</beans>";
        }
        Factory bean() { return context.getBean("factory", Factory.class); }
        AbstractBeanDefinition definition() { return (AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition("factory"); }
        List<String> overrides() {
            return definition().getMethodOverrides().getOverrides().stream()
                    .map(o -> o.getMethodName() + ":" + ((LookupOverride) o).getBeanName()).toList();
        }
        void reload(String overrides, String label, String otherLabel) throws Exception {
            Files.writeString(xml, document(overrides, label, otherLabel)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener);
            try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(unrelated, context.getBean("unrelated"));
        }
        void assertPreserved() {
            if (held != null) assertSame(held, bean());
            assertSame(originalResult, bean().select());
            assertEquals(originalOverrides, overrides());
        }
        void assertWarning() {
            assertTrue(events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("factory") && v.contains("lookup-method")), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("factory") && v.contains("lookup-method")), RestartLedger.digest().toString());
        }
        void assertNoWarning() {
            assertFalse(events.stream().anyMatch(v -> v.contains("Restart required")), events.toString());
            assertEquals(List.of("Nothing from this session needs a restart."), RestartLedger.digest());
        }
        public void close() { context.close(); }
    }
}
