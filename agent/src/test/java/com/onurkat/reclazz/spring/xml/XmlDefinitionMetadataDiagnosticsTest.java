/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlDefinitionMetadataDiagnosticsTest {
    @TempDir Path tmp;
    static final List<String> order = new ArrayList<>();
    public static class First { public First() { order.add("A"); } }
    public static class Second { public Second() { order.add("B"); } }
    public static class Settings {
        String label;
        First first;
        public Settings() { order.add("T"); }
        public void setLabel(String value) { label = value; }
        public void setFirst(First value) { first = value; }
        public void init() { }
    }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); order.clear(); }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); order.clear(); }

    @ParameterizedTest
    @CsvSource(value = {"~|a", "a|~", "a|b", "a,b|b,a"}, delimiter = '|')
    void dependsOnAddRemoveRetargetAndOrderRequireRestart(String before, String after) throws Exception {
        try (var s = new Scope(depends(before), true)) {
            Object raw = s.raw(), merged = s.merged();
            s.reload(depends(after), "after", "after");
            s.preserved(raw, merged); s.warning("depends-on");
            assertFalse(s.context.getBeanFactory().containsSingleton("settings"));
            assertFalse(s.context.getBeanFactory().containsSingleton("a"));
            assertFalse(s.context.getBeanFactory().containsSingleton("b"));
            assertEquals("after", s.unrelated.label);
        }
    }
    @ParameterizedTest
    @CsvSource({"false,true", "true,false"})
    void primaryChangesRequireRestart(String before, String after) throws Exception {
        changed("primary='" + before + "'", "primary='" + after + "'", "primary");
    }
    @ParameterizedTest
    @CsvSource({"no,byType", "byType,no", "byName,byType"})
    void autowireModeChangesRequireRestart(String before, String after) throws Exception {
        changed("autowire='" + before + "'", "autowire='" + after + "'", "autowire");
    }
    @ParameterizedTest
    @CsvSource({"true,false", "false,true"})
    void candidateChangesRequireRestart(String before, String after) throws Exception {
        changed("autowire-candidate='" + before + "'", "autowire-candidate='" + after + "'", "autowire-candidate");
    }
    private void changed(String before, String after, String reason) throws Exception {
        try (var s = new Scope(before, false)) {
            Object raw = s.raw(), merged = s.merged();
            Settings held = s.bean(); Object originalDependency = held.first;
            s.reload(after, "after", "after");
            s.preserved(raw, merged); s.warning(reason);
            assertSame(held, s.bean()); assertSame(originalDependency, held.first);
            assertEquals("before", held.label); assertEquals("after", s.unrelated.label);
            assertFalse(s.events.stream().anyMatch(v -> v.contains("Spring property updated: settings.")), s.events.toString());
        }
    }
    @Test void lazyInitializationKeepsOriginalDependencyOrder() throws Exception {
        try (var s = new Scope("depends-on='a'", true)) {
            order.clear();
            s.reload("depends-on='b'", "after", "after"); s.warning("depends-on");
            assertEquals(List.of(), order, "reload must not instantiate lazy target or dependencies");
            assertEquals("before", s.bean().label); assertEquals(List.of("A", "T"), order);
        }
    }
    @Test void unchangedMetadataAllowsOrdinaryPropertyEditsAndNoOp() throws Exception {
        String attributes = "depends-on='a, b' primary='true' autowire='byType' autowire-candidate='false'";
        try (var s = new Scope(attributes, false)) {
            Settings held = s.bean(); Object raw = s.raw(), merged = s.merged();
            s.reload("depends-on='a b' primary='true' autowire='byType' autowire-candidate='false'", "before", "before");
            s.preserved(raw, merged); s.noWarning();
            s.reload(attributes, "after", "after");
            assertSame(held, s.bean()); assertEquals("after", held.label); s.noWarning();
        }
    }
    @Test void explicitDefaultsAreEquivalentToOmittedMetadata() throws Exception {
        try (var s = new Scope("", false)) {
            s.reload("primary='false' autowire='no' autowire-candidate='true' depends-on=''", "after", "after");
            assertEquals("after", s.bean().label); s.noWarning();
        }
    }
    @Test void repeatedMixedEditsPreserveTargetAndRevertingMetadataAllowsPropertyUpdate() throws Exception {
        try (var s = new Scope("primary='false'", false)) {
            Object raw = s.raw(), merged = s.merged(); Settings held = s.bean();
            for (int i = 0; i < 2; i++) {
                s.reload("primary='true'", "rejected", "other" + i);
                s.preserved(raw, merged); s.warning("primary");
                assertEquals("before", held.label); assertEquals("other" + i, s.unrelated.label);
                assertTrue(s.events.stream().anyMatch(v -> v.contains("1 change applied, 1 require restart")), s.events.toString());
            }
            s.reload("primary='false'", "corrected", "final");
            assertSame(held, s.bean()); assertEquals("corrected", held.label); s.noWarning();
        }
    }
    @Test void allChangedFieldsAreReportedBeforeMutation() throws Exception {
        try (var s = new Scope("", false)) {
            Object raw = s.raw(), merged = s.merged();
            s.reload("depends-on='a' primary='true' autowire='byType' autowire-candidate='false'", "after", "after");
            s.preserved(raw, merged);
            for (String field : List.of("depends-on", "primary", "autowire", "autowire-candidate")) s.warning(field);
            assertEquals("before", s.bean().label);
        }
    }
    @Test void newDefinitionUsesNativeMetadata() throws Exception {
        try (var s = new Scope("", false)) {
            s.extra = "<bean id='added' class='" + Settings.class.getName()
                    + "' depends-on='a' primary='true' autowire='byType' autowire-candidate='false'/>";
            s.reload("", "before", "before");
            Settings added = s.context.getBean("added", Settings.class);
            assertSame(s.context.getBean("a"), added.first);
            AbstractBeanDefinition bd = (AbstractBeanDefinition) s.context.getBeanFactory().getBeanDefinition("added");
            assertArrayEquals(new String[]{"a"}, bd.getDependsOn()); assertTrue(bd.isPrimary());
            assertFalse(bd.isAutowireCandidate());
            assertFalse(s.events.stream().anyMatch(v -> v.contains("Restart required")), s.events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("added") && v.contains("was added")),
                    "existing added-bean consumer guidance remains");
        }
    }
    @Test void richDefinitionGuardStillRefusesUnsupportedOrderMetadata() throws Exception {
        try (var s = new Scope("init-method='init'", false)) {
            Settings held = s.bean();
            s.reload("init-method='init' depends-on='a'", "after", "after");
            assertSame(held, s.bean()); assertEquals("before", held.label);
            assertTrue(s.events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("autowire/depends-on")), s.events.toString());
        }
    }
    @Test void bothOwningContextsAreGuardedAndUnrelatedContextIsUntouched() throws Exception {
        try (var s = new Scope("primary='false'", false);
             var second = load(s.xml);
             var unrelated = new GenericApplicationContext()) {
            unrelated.registerBean("settings", Settings.class, () -> { var v = new Settings(); v.label = "isolated"; return v; });
            unrelated.refresh();
            Settings secondHeld = second.getBean("settings", Settings.class), isolated = unrelated.getBean("settings", Settings.class);
            ApplicationContextHolder.register(second); ApplicationContextHolder.register(unrelated);
            s.reload("primary='true'", "after", "after"); s.warning("primary");
            assertEquals("before", s.bean().label); assertEquals("before", secondHeld.label);
            assertSame(secondHeld, second.getBean("settings"));
            assertEquals("after", second.getBean("unrelated", Settings.class).label);
            assertSame(isolated, unrelated.getBean("settings")); assertEquals("isolated", isolated.label);
            assertFalse(second.getBeanFactory().getBeanDefinition("settings").isPrimary());
        }
    }
    @Test void unavailableMetadataRefusesInsteadOfAssumingEquality() {
        var diff = new BeanDefinitionDiff();
        assertTrue(XmlDefinitionMetadataGuard.classify("settings", new Object(), new Object(), diff));
        assertEquals(1, diff.unsafe.size());
        assertTrue(diff.unsafe.get(0).reason.contains("cannot verify definition metadata"));
        assertTrue(diff.propertyChanges.isEmpty()); assertTrue(diff.recreated.isEmpty());
    }
    private static String depends(String value) { return value.equals("~") ? "" : "depends-on='" + value + "'"; }
    private static GenericApplicationContext load(Path xml) {
        var context = new GenericApplicationContext();
        var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
        var resource = new FileSystemResource(xml.toFile());
        reader.loadBeanDefinitions(resource);
        // The real javaagent records successful loads; component tests explicitly record ownership.
        SpringXmlResources.record(reader, resource);
        context.refresh(); return context;
    }
    private final class Scope implements AutoCloseable {
        final Path xml;
        final boolean lazy;
        final GenericApplicationContext context;
        final SpringXmlReloader reloader;
        final List<String> events = new ArrayList<>();
        final Settings unrelated;
        String extra = "";
        Scope(String attributes, boolean lazy) throws Exception {
            this.lazy = lazy;
            xml = Files.createTempFile(tmp, "metadata-", ".xml");
            Files.writeString(xml, document(attributes, "before", "before"));
            context = load(xml); unrelated = context.getBean("unrelated", Settings.class);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getApplicationContext" -> context;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            reloader = new SpringXmlReloader(platform);
        }
        String document(String attributes, String label, String other) {
            return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + "<bean id='a' class='" + First.class.getName() + "' lazy-init='true'/>"
                    + "<bean id='b' class='" + Second.class.getName() + "' lazy-init='true'/>"
                    + "<bean id='settings' class='" + Settings.class.getName() + "' lazy-init='" + lazy + "' " + attributes
                    + "><property name='label' value='" + label + "'/></bean>"
                    + "<bean id='unrelated' class='" + Settings.class.getName() + "'><property name='label' value='" + other + "'/></bean>"
                    + extra + "</beans>";
        }
        Settings bean() { return context.getBean("settings", Settings.class); }
        AbstractBeanDefinition raw() { return (AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition("settings"); }
        Object merged() { return context.getBeanFactory().getMergedBeanDefinition("settings"); }
        void reload(String attributes, String label, String other) throws Exception {
            Files.writeString(xml, document(attributes, label, other)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener);
            try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(unrelated, context.getBean("unrelated"));
        }
        void preserved(Object raw, Object merged) {
            assertSame(raw, raw()); assertSame(merged, merged());
            assertEquals("before", ((TypedStringValue) raw().getPropertyValues().get("label")).getValue());
            assertEquals("before", ((TypedStringValue) ((AbstractBeanDefinition) merged()).getPropertyValues().get("label")).getValue());
        }
        void warning(String field) {
            assertTrue(events.stream().anyMatch(v -> v.contains("Restart required") && v.contains("settings") && v.contains(field)), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("settings") && v.contains(field)), RestartLedger.digest().toString());
        }
        void noWarning() {
            assertFalse(events.stream().anyMatch(v -> v.contains("Restart required")), events.toString());
            assertEquals(List.of("Nothing from this session needs a restart."), RestartLedger.digest());
        }
        public void close() { context.close(); }
    }
}
