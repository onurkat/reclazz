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
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlPropertyFailureIntegrityTest {
    @TempDir Path tmp;
    public static class Value { }
    public static class Settings {
        String label;
        int number;
        Value dependency;
        public void setLabel(String value) {
            if (value.equals("reject")) throw new IllegalArgumentException("rejected label");
            label = value;
            if (value.equals("partial")) throw new IllegalArgumentException("label changed before failure");
        }
        public void setNumber(int value) { number = value; }
        public void setDependency(Value value) { dependency = value; }
    }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @Test void missingReferencePreservesDefinition() throws Exception {
        try (var s = new Scope(false)) {
            Object raw = s.raw("dependency"), merged = s.merged("dependency");
            s.reload(properties("old", "1", "missing"), "untouched");
            assertSame(s.a, s.bean().dependency); assertSame(raw, s.raw("dependency"));
            assertSame(merged, s.merged("dependency")); s.failure("dependency");
        }
    }
    @Test void wrongPrimitivePreservesDefinition() throws Exception {
        try (var s = new Scope(false)) {
            Object raw = s.raw("number");
            s.reload(properties("old", "not-a-number", "a"), "untouched");
            assertEquals(1, s.bean().number); assertSame(raw, s.raw("number")); s.failure("number");
        }
    }
    @Test void incompatibleReferencePreservesDefinition() throws Exception {
        try (var s = new Scope(false)) {
            Object raw = s.raw("dependency");
            s.reload(properties("old", "1", "incompatible"), "untouched");
            assertSame(s.a, s.bean().dependency); assertSame(raw, s.raw("dependency")); s.failure("dependency");
        }
    }
    @Test void missingSetterDoesNotAddProperty() throws Exception {
        try (var s = new Scope(false)) {
            s.reload(properties("old", "1", "a") + property("unknown", "new"), "untouched");
            assertNull(s.raw("unknown")); assertNull(s.merged("unknown")); s.failure("unknown");
        }
    }
    @Test void rejectingSetterPreservesDefinition() throws Exception {
        try (var s = new Scope(false)) {
            Object raw = s.raw("label");
            s.reload(properties("reject", "1", "a"), "untouched");
            assertEquals("old", s.bean().label); assertSame(raw, s.raw("label")); s.failure("label");
        }
    }
    @Test void mutatingThrowingSetterPreservesMetadataOnly() throws Exception {
        try (var s = new Scope(false)) {
            Object raw = s.raw("label");
            s.reload(properties("partial", "1", "a"), "untouched");
            assertEquals("partial", s.bean().label, "application setter effects are not rolled back");
            assertSame(raw, s.raw("label")); s.failure("label");
            s.reload(properties("corrected", "1", "a"), "untouched");
            assertEquals("corrected", s.bean().label); s.noFailure();
        }
    }
    @Test void mixedChangesApplySuccessfulProperties() throws Exception {
        try (var s = new Scope(false)) {
            Object raw = s.raw("number");
            s.reload(properties("after", "invalid", "b"), "after");
            assertEquals("after", s.bean().label); assertSame(s.b, s.bean().dependency);
            assertEquals(1, s.bean().number); assertSame(raw, s.raw("number"));
            assertEquals("after", s.unrelated.label); s.failure("number");
            assertTrue(s.events.stream().anyMatch(v -> v.contains("3 changes applied, 1 require restart")), s.events.toString());
        }
    }
    @Test void repeatedFailureThenCorrectedSaveRecovers() throws Exception {
        try (var s = new Scope(false)) {
            Object raw = s.raw("dependency");
            for (int i = 0; i < 2; i++) {
                s.reload(properties("old", "1", "missing"), "untouched");
                assertSame(raw, s.raw("dependency")); assertSame(s.a, s.bean().dependency); s.failure("dependency");
            }
            s.reload(properties("after", "2", "b"), "untouched");
            assertSame(s.b, s.bean().dependency); assertEquals(2, s.bean().number);
            assertEquals("b", ((RuntimeBeanReference) s.raw("dependency")).getBeanName()); s.noFailure();
        }
    }
    @Test void preservedRawDefinitionCanBeNativelyRecreated() throws Exception {
        try (var s = new Scope(false)) {
            Settings previous = s.bean();
            s.reload(properties("old", "1", "missing"), "untouched"); s.failure("dependency");
            // Native re-registration invalidates merged metadata and exposes the raw definition.
            // It is a proof step, not an extra action introduced by the reloader.
            var factory = s.context.getDefaultListableBeanFactory();
            factory.registerBeanDefinition("settings", factory.getBeanDefinition("settings"));
            Settings recreated = s.context.getBean("settings", Settings.class);
            assertNotSame(previous, recreated); assertSame(s.a, recreated.dependency);
            assertEquals("old", recreated.label); assertEquals(1, recreated.number);
        }
    }
    @Test void validReferenceCommitsDefinition() throws Exception {
        try (var s = new Scope(false)) {
            Settings held = s.bean();
            s.reload(properties("new", "2", "b"), "untouched");
            assertSame(held, s.bean()); assertSame(s.b, held.dependency); assertEquals(2, held.number);
            assertEquals("b", ((RuntimeBeanReference) s.raw("dependency")).getBeanName());
            assertEquals("new", ((TypedStringValue) s.raw("label")).getValue()); s.noFailure();
        }
    }
    @Test void lazyValidUpdateDoesNotInstantiate() throws Exception {
        try (var s = new Scope(true)) {
            assertFalse(s.context.getBeanFactory().containsSingleton("settings"));
            Object oldMerged = s.context.getBeanFactory().getMergedBeanDefinition("settings");
            Object unrelatedMerged = s.context.getBeanFactory().getMergedBeanDefinition("unrelated");
            s.reload(properties("after", "2", "b"), "untouched");
            assertFalse(s.context.getBeanFactory().containsSingleton("settings"));
            assertNotSame(oldMerged, s.context.getBeanFactory().getMergedBeanDefinition("settings"));
            assertSame(unrelatedMerged, s.context.getBeanFactory().getMergedBeanDefinition("unrelated"));
            assertEquals("after", s.bean().label); assertEquals(2, s.bean().number);
            assertSame(s.b, s.bean().dependency); s.noFailure();
        }
    }
    @Test void lazyInvalidValueRetainsDeferredValidation() throws Exception {
        try (var s = new Scope(true)) {
            s.reload(properties("old", "invalid", "a"), "untouched");
            assertFalse(s.context.getBeanFactory().containsSingleton("settings"));
            assertEquals("invalid", ((TypedStringValue) s.raw("number")).getValue());
            s.noFailure();
            assertThrows(BeansException.class, s::bean, "lazy validation stays deferred to native creation");
        }
    }
    private static String property(String name, String value) {
        return "<property name='" + name + "' value='" + value + "'/>";
    }
    private static String properties(String label, String number, String ref) {
        return property("label", label) + property("number", number) + "<property name='dependency' ref='" + ref + "'/>";
    }
    private final class Scope implements AutoCloseable {
        final GenericApplicationContext context = new GenericApplicationContext();
        final Path xml;
        final boolean lazy;
        final SpringXmlReloader reloader;
        final List<String> events = new ArrayList<>();
        final Value a, b;
        final Settings unrelated, held;
        Scope(boolean lazy) throws Exception {
            this.lazy = lazy;
            xml = Files.createTempFile(tmp, "integrity-", ".xml");
            Files.writeString(xml, document(properties("old", "1", "a"), "untouched"));
            var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
            reader.loadBeanDefinitions(new FileSystemResource(xml.toFile())); context.refresh();
            a = context.getBean("a", Value.class); b = context.getBean("b", Value.class);
            held = lazy ? null : bean(); unrelated = context.getBean("unrelated", Settings.class);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getApplicationContext" -> context;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            reloader = new SpringXmlReloader(platform);
        }
        String document(String properties, String other) {
            return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + "<bean id='a' class='" + Value.class.getName() + "'/><bean id='b' class='" + Value.class.getName() + "'/>"
                    + "<bean id='incompatible' class='java.lang.Object'/>"
                    + "<bean id='settings' class='" + Settings.class.getName() + "' lazy-init='" + lazy + "'>" + properties + "</bean>"
                    + "<bean id='unrelated' class='" + Settings.class.getName() + "'>" + property("label", other) + "</bean></beans>";
        }
        Settings bean() { return context.getBean("settings", Settings.class); }
        Object raw(String name) { return context.getBeanFactory().getBeanDefinition("settings").getPropertyValues().get(name); }
        Object merged(String name) { return context.getBeanFactory().getMergedBeanDefinition("settings").getPropertyValues().get(name); }
        void reload(String properties, String other) throws Exception {
            Files.writeString(xml, document(properties, other)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener);
            try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(unrelated, context.getBean("unrelated"));
            if (held != null) assertSame(held, bean());
        }
        void failure(String property) {
            assertTrue(events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("settings." + property) && v.contains("property apply failed")), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("settings." + property) && v.contains("property apply failed")), RestartLedger.digest().toString());
            assertFalse(events.stream().anyMatch(v -> v.contains("Spring property updated: settings." + property)), events.toString());
        }
        void noFailure() { assertFalse(events.stream().anyMatch(v -> v.contains("property apply failed")), events.toString()); }
        public void close() { context.close(); }
    }
}
