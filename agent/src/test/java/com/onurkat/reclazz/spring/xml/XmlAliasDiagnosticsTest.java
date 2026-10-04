/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
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

class XmlAliasDiagnosticsTest {
    @TempDir Path tmp;
    final List<GenericApplicationContext> contexts = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    @AfterEach void close() { contexts.forEach(GenericApplicationContext::close); ApplicationContextHolder.clear(); RestartLedger.clear(); }
    public static class Value {
        public String text;
        public void setText(String text) { this.text = text; }
        public void init() { }
    }
    static String bean(String name) { return "<bean id='" + name + "' class='" + Value.class.getName() + "'/>"; }
    static String alias(String target, String name) { return "<alias name='" + target + "' alias='" + name + "'/>"; }
    Path write(String name, String body) throws Exception {
        Path path = tmp.resolve(name); Files.createDirectories(path.getParent());
        Files.writeString(path, "<beans xmlns='http://www.springframework.org/schema/beans'>" + body + "</beans>");
        return path;
    }
    GenericApplicationContext context(Path... paths) throws Exception {
        return contextWithProfiles(new String[0], paths);
    }
    GenericApplicationContext contextWithProfiles(String[] profiles, Path... paths) throws Exception {
        var context = new GenericApplicationContext(); contexts.add(context);
        context.getEnvironment().setActiveProfiles(profiles);
        var reader = new ObservedReader(context); reader.setValidating(false);
        // Unit seam for the same native registration events captured by the agent hooks.
        SpringXmlAliases.observeReader(reader, getClass().getClassLoader());
        for (Path path : paths) {
            var resource = new FileSystemResource(path);
            SpringXmlAliases.begin(reader, resource);
            reader.loadBeanDefinitions(resource);
            SpringXmlResources.record(reader, resource);
        }
        context.refresh(); ApplicationContextHolder.register(context); return context;
    }
    public static class ObservedReader extends XmlBeanDefinitionReader {
        ObservedReader(GenericApplicationContext context) { super(context); }
        @Override protected int doLoadBeanDefinitions(org.xml.sax.InputSource source,
                                                       org.springframework.core.io.Resource resource) {
            SpringXmlAliases.begin(this, resource);
            int count = super.doLoadBeanDefinitions(source, resource);
            SpringXmlResources.record(this, resource);
            return count;
        }
    }
    void reload(Path path, GenericApplicationContext primary) {
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getApplicationContext" -> primary;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        events.clear(); RestartLedger.clear();
        StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
        StatusReporter.addListener(listener);
        try { new SpringXmlReloader(platform).reloadLoaded(path); }
        finally { StatusReporter.removeListener(listener); }
    }
    void warned(String name) {
        assertTrue(events.stream().anyMatch(v -> v.contains("alias") && v.contains(name) && v.contains("restart")), events.toString());
        assertTrue(RestartLedger.digest().stream().anyMatch(v -> v.contains("alias") && v.contains(name)), RestartLedger.digest().toString());
    }
    void noAliasWarning() { assertFalse(events.stream().anyMatch(v -> v.contains("alias") && v.contains("restart")), events.toString()); }

    @Test void retargetPreservesLookupAndHeldReference() throws Exception {
        Path file = write("settings.xml", bean("a") + bean("b") + alias("a", "chosen"));
        var c = context(file); Object held = c.getBean("chosen");
        write("settings.xml", bean("a") + bean("b") + alias("b", "chosen")); reload(file, c);
        assertSame(held, c.getBean("chosen")); assertSame(c.getBean("a"), held); warned("chosen");
    }
    @Test void additionAndRemovalInAliasOnlyResourceAreReportedRepeatedly() throws Exception {
        Path defs = write("defs.xml", bean("a")); Path file = write("aliases.xml", alias("a", "oldName"));
        var c = context(defs, file); Object held = c.getBean("oldName");
        for (int i = 0; i < 2; i++) {
            write("aliases.xml", alias("a", "newName")); reload(file, c);
            assertSame(held, c.getBean("oldName")); assertFalse(c.containsBean("newName"));
            warned("oldName"); warned("newName");
        }
    }
    @Test void removalAloneWarnsEvenWithNoDefinitionsInFile() throws Exception {
        Path defs = write("defs.xml", bean("a")); Path file = write("aliases.xml", alias("a", "chosen"));
        var c = context(defs, file); write("aliases.xml", ""); reload(file, c);
        assertSame(c.getBean("a"), c.getBean("chosen")); warned("chosen");
    }
    @Test void unchangedExternalAliasDoesNotBelongToBeanResource() throws Exception {
        Path defs = write("defs.xml", bean("a")); Path file = write("aliases.xml", alias("a", "chosen"));
        var c = context(defs, file); reload(defs, c); noAliasWarning(); reload(file, c); noAliasWarning();
    }
    @Test void inlineAliasChangeIsReported() throws Exception {
        Path file = write("settings.xml", bean("a").replace("id='a'", "id='a' name='chosen'"));
        var c = context(file); write("settings.xml", bean("a")); reload(file, c);
        assertSame(c.getBean("a"), c.getBean("chosen")); warned("chosen");
    }
    @Test void newBeanAliasIsExplicitlyUnapplied() throws Exception {
        Path file = write("settings.xml", bean("a")); var c = context(file);
        write("settings.xml", bean("a") + bean("b") + alias("b", "chosen")); reload(file, c);
        assertTrue(c.containsBean("b")); assertFalse(c.containsBean("chosen")); warned("chosen");
    }
    @Test void ordinaryPropertyStillAppliesAlongsideAliasWarning() throws Exception {
        Path file = write("settings.xml", bean("a") + alias("a", "chosen")); var c = context(file);
        Value held = c.getBean("a", Value.class);
        write("settings.xml", bean("a").replace("/>", "><property name='text' value='after'/></bean>")); reload(file, c);
        assertEquals("after", held.text); assertSame(held, c.getBean("chosen")); warned("chosen");
    }
    @Test void sameBasenameAndSharedResourceKeepContextsIsolated() throws Exception {
        Path file = write("one/settings.xml", bean("a") + alias("a", "chosen"));
        Path other = write("two/settings.xml", bean("a") + alias("a", "chosen"));
        var first = context(file); var second = context(file); var third = context(other);
        Object unrelated = third.getBean("chosen");
        write("one/settings.xml", bean("a") + alias("a", "next")); reload(file, first);
        assertSame(first.getBean("a"), first.getBean("chosen"));
        assertSame(second.getBean("a"), second.getBean("chosen"));
        assertSame(unrelated, third.getBean("chosen")); assertFalse(third.containsBean("next")); warned("next");
        reload(other, third); noAliasWarning();
    }
    @Test void invalidCycleNeverChangesLiveAliasesOrBeans() throws Exception {
        Path file = write("settings.xml", bean("a") + alias("a", "chosen")); var c = context(file);
        Object held = c.getBean("a");
        write("settings.xml", bean("a") + alias("b", "chosen") + alias("chosen", "b")); reload(file, c);
        assertSame(held, c.getBean("chosen")); assertFalse(c.containsBean("b"));
        assertTrue(events.stream().anyMatch(v -> v.contains("Failed to parse")), events.toString());
    }
    @Test void richBeanAliasGuardStillRefusesRecreation() throws Exception {
        String definition = bean("a").replace("/>", " init-method='init'/>");
        Path file = write("settings.xml", definition + alias("a", "chosen")); var c = context(file); Object held = c.getBean("a");
        write("settings.xml", definition); reload(file, c);
        assertSame(held, c.getBean("a")); assertSame(held, c.getBean("chosen")); warned("chosen");
        assertTrue(events.stream().anyMatch(v -> v.contains("alias changes require restart")), events.toString());
    }
    @Test void activeProfilesDoNotProduceFalseRemovalWarnings() throws Exception {
        Path file = write("settings.xml", bean("a")
                + "<beans profile='selected'>" + alias("a", "chosen") + "</beans>"
                + "<beans profile='unused'>" + alias("a", "inactive") + "</beans>");
        var c = contextWithProfiles(new String[]{"selected"}, file);
        assertTrue(c.containsBean("chosen")); assertFalse(c.containsBean("inactive"));
        reload(file, c); noAliasWarning();
    }
    @Test void missingBaselineIsReportedAsUnverified() throws Exception {
        Path file = write("settings.xml", bean("a") + alias("a", "chosen"));
        var c = new GenericApplicationContext(); contexts.add(c);
        var reader = new XmlBeanDefinitionReader(c); reader.setValidating(false);
        reader.loadBeanDefinitions(new FileSystemResource(file));
        // Simulates resource observation without the earlier alias-event baseline.
        SpringXmlResources.record(reader, new FileSystemResource(file));
        c.refresh(); ApplicationContextHolder.register(c); reload(file, c);
        assertTrue(events.stream().anyMatch(v -> v.contains("alias ownership baseline unavailable")), events.toString());
        assertFalse(events.stream().anyMatch(v -> v.contains("alias declaration removed")), events.toString());
        assertSame(c.getBean("a"), c.getBean("chosen"));
    }
    @Test void importedAliasOwnershipIsSeparateFromImportingDocument() throws Exception {
        Path imported = write("nested/aliases.xml", alias("a", "chosen"));
        Path root = write("root.xml", bean("a") + "<import resource='nested/aliases.xml'/>");
        var c = context(root); reload(root, c); noAliasWarning();
        write("nested/aliases.xml", ""); reload(imported, c); warned("chosen");
        assertSame(c.getBean("a"), c.getBean("chosen"));
        reload(root, c); noAliasWarning(); // The importer's own declaration set did not change.
    }
    @Test void unsuccessfulNativeParseDoesNotPublishPartialAliasBaseline() throws Exception {
        Path file = write("settings.xml", bean("a") + alias("a", "chosen")); var c = context(file);
        var reader = new ObservedReader(c); reader.setValidating(false);
        SpringXmlAliases.observeReader(reader, getClass().getClassLoader());
        write("settings.xml", alias("a", "partial") + alias("bad", "loop") + alias("loop", "bad"));
        assertThrows(org.springframework.beans.factory.BeanDefinitionStoreException.class,
                () -> reader.loadBeanDefinitions(new FileSystemResource(file)));
        assertEquals(Map.of("chosen", "a"), SpringXmlAliases.snapshot(c, file));
    }
}
