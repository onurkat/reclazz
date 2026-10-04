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
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class XmlPropertyRemovalDiagnosticsTest {
    @TempDir Path tmp;
    public static class Settings {
        String retained = "default", edited, added, extra;
        public void setRetained(String value) { retained = value; }
        public void setEdited(String value) { edited = value; }
        public void setAdded(String value) { added = value; }
        public void setExtra(String value) { extra = value; }
    }
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    @AfterEach void clear() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @Test void removalWithEditWarnsAndKeepsRemovedValue() throws Exception {
        try (var s = new Scope(false)) {
            Settings before = s.bean();
            s.reload(property("edited", "after"));
            assertSame(before, s.bean());
            assertEquals("after", before.edited);
            s.assertRetainedAndWarned();
        }
    }
    @Test void removalWithAdditionAtSameCountWarns() throws Exception {
        try (var s = new Scope(false)) {
            s.reload(property("edited", "before") + property("added", "new"));
            assertEquals("new", s.bean().added);
            assertEquals("before", s.bean().edited);
            s.assertRetainedAndWarned();
        }
    }
    @Test void removalWithMoreAdditionsWarns() throws Exception {
        try (var s = new Scope(false)) {
            s.reload(property("edited", "after") + property("added", "new") + property("extra", "extra"));
            assertEquals("after", s.bean().edited);
            assertEquals("new", s.bean().added);
            assertEquals("extra", s.bean().extra);
            s.assertRetainedAndWarned();
        }
    }
    @Test void removalOnlyWarns() throws Exception {
        try (var s = new Scope(false)) {
            s.reload(property("edited", "before"));
            assertEquals("before", s.bean().edited);
            s.assertRetainedAndWarned();
        }
    }
    @Test void editOnlyDoesNotWarn() throws Exception {
        try (var s = new Scope(false)) {
            s.reload(property("retained", "old") + property("edited", "after"));
            assertEquals("after", s.bean().edited);
            s.assertNoRemovalWarning();
        }
    }
    @Test void unchangedPropertiesDoNotWarn() throws Exception {
        try (var s = new Scope(false)) {
            s.reload(property("edited", "before") + property("retained", "old"));
            assertEquals("old", s.bean().retained);
            assertEquals("before", s.bean().edited);
            s.assertNoRemovalWarning();
        }
    }
    @Test void repeatedRemovalKeepsWarningAndIdentity() throws Exception {
        try (var s = new Scope(false)) {
            Settings before = s.bean();
            for (String value : List.of("after", "after", "later")) {
                s.reload(property("edited", value));
                assertSame(before, s.bean()); assertEquals(value, before.edited);
                s.assertRetainedAndWarned();
            }
        }
    }
    @Test void lazyDefinitionRetainsRemovedValue() throws Exception {
        try (var s = new Scope(true)) {
            assertFalse(s.context.getBeanFactory().containsSingleton("settings"));
            s.reload(property("edited", "after"));
            assertFalse(s.context.getBeanFactory().containsSingleton("settings"));
            s.assertRetainedAndWarned();
            assertEquals("after", s.bean().edited);
        }
    }
    private static String property(String name, String value) {
        return "<property name='" + name + "' value='" + value + "'/>";
    }
    private final class Scope implements AutoCloseable {
        final GenericApplicationContext context = new GenericApplicationContext();
        final Path xml;
        final boolean lazy;
        final SpringXmlReloader reloader;
        final List<String> events = new ArrayList<>();
        final Object retainedRaw;
        final Settings unrelated;
        Scope(boolean lazy) throws Exception {
            this.lazy = lazy;
            xml = Files.createTempFile(tmp, "removal-", ".xml");
            Files.writeString(xml, document(property("retained", "old") + property("edited", "before")));
            var reader = new XmlBeanDefinitionReader(context); reader.setValidating(false);
            reader.loadBeanDefinitions(new FileSystemResource(xml.toFile())); context.refresh();
            retainedRaw = context.getBeanFactory().getBeanDefinition("settings").getPropertyValues().get("retained");
            unrelated = context.getBean("unrelated", Settings.class);
            PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PlatformContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getApplicationContext" -> context;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            reloader = new SpringXmlReloader(platform);
        }
        String document(String properties) {
            return "<beans xmlns='http://www.springframework.org/schema/beans'><bean id='settings' class='"
                    + Settings.class.getName() + "' lazy-init='" + lazy + "'>" + properties + "</bean>"
                    + "<bean id='unrelated' class='" + Settings.class.getName() + "'>"
                    + property("retained", "untouched") + "</bean></beans>";
        }
        Settings bean() { return context.getBean("settings", Settings.class); }
        void reload(String properties) throws Exception {
            Files.writeString(xml, document(properties)); events.clear(); RestartLedger.clear();
            StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
            StatusReporter.addListener(listener);
            try { reloader.reload(xml); } finally { StatusReporter.removeListener(listener); }
            assertSame(unrelated, context.getBean("unrelated"));
            assertEquals("untouched", unrelated.retained);
        }
        void assertRetainedAndWarned() {
            assertSame(retainedRaw, context.getBeanFactory().getBeanDefinition("settings").getPropertyValues().get("retained"));
            assertEquals("old", bean().retained, "removal does not reset to the default");
            assertTrue(events.stream().anyMatch(v -> v.contains("Restart required")
                    && v.contains("settings") && v.contains("<property> removed")), events.toString());
            assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("settings")
                    && v.contains("<property> removed")), RestartLedger.digest().toString());
        }
        void assertNoRemovalWarning() {
            assertFalse(events.stream().anyMatch(v -> v.contains("<property> removed")), events.toString());
            assertEquals(List.of("Nothing from this session needs a restart."), RestartLedger.digest());
        }
        public void close() { context.close(); }
    }
}
