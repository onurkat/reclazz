/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringXmlResourceRecognitionTest {
    @TempDir Path tmp;
    private final List<GenericApplicationContext> contexts = new ArrayList<>();
    @BeforeEach void reset() { ApplicationContextHolder.clear(); }
    @AfterEach void close() {
        contexts.forEach(GenericApplicationContext::close);
        ApplicationContextHolder.clear();
    }

    private GenericApplicationContext context(Path xml, boolean factoryRegistry) {
        var context = new GenericApplicationContext();
        contexts.add(context);
        var reader = new XmlBeanDefinitionReader(factoryRegistry ? context.getDefaultListableBeanFactory() : context);
        reader.setValidating(false);
        var resource = new FileSystemResource(xml);
        reader.loadBeanDefinitions(resource);
        // Unit seam for the successful-reader hook; real instrumentation is covered in e2e.
        SpringXmlResources.record(reader, resource);
        context.refresh();
        ApplicationContextHolder.register(context);
        return context;
    }
    private Path empty(String name) throws Exception {
        Path xml = tmp.resolve(name);
        Files.createDirectories(xml.getParent());
        Files.writeString(xml, "<beans xmlns='http://www.springframework.org/schema/beans'/>");
        return xml;
    }
    private PlatformContext platform() { return new TestPlatform(); }

    @Test void onlyExactLoadedPathIsRecognizedAndStringConventionIsUnchanged() throws Exception {
        Path loaded = empty("one/spring-mvc-config.xml");
        Path unrelated = empty("two/spring-mvc-config.xml");
        var owner = context(loaded, false);
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(loaded.getFileName().toString()));
        assertEquals(ChangeKind.SPRING_XML, ChangeKind.of(loaded));
        assertEquals(ChangeKind.SPRING_XML, ChangeKind.of(loaded.getParent().resolve("../one/spring-mvc-config.xml")));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(unrelated));
        assertEquals(List.of(owner), SpringXmlResources.owners(loaded, contextsAsObjects()));
    }

    @Test void factoryRegistryIsRecognizedButSandboxAndClosedContextsAreNot() throws Exception {
        Path loaded = empty("settings.xml");
        var owner = context(loaded, true);
        assertTrue(SpringXmlResources.isLoaded(loaded));
        Path sandbox = empty("sandbox.xml");
        var reader = new XmlBeanDefinitionReader(new org.springframework.beans.factory.support.DefaultListableBeanFactory());
        SpringXmlResources.record(reader, new FileSystemResource(sandbox));
        assertFalse(SpringXmlResources.isLoaded(sandbox));
        owner.close();
        assertFalse(SpringXmlResources.isLoaded(loaded));
        assertFalse(SpringXmlResources.loadedFiles().contains(loaded));
        // No fallback to another active context after the owner closed.
        var other = context(empty("other.xml"), false);
        Files.writeString(loaded, "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<bean id='unwanted' class='java.lang.Object'/></beans>");
        new SpringXmlReloader(platform()).reloadLoaded(loaded);
        assertFalse(other.containsBean("unwanted"));
    }

    @Test void duplicateObservationHasOneOwnerAndRemoteResourcesAreNotClaimed() throws Exception {
        Path file = empty("settings.xml");
        var owner = context(file, false);
        var reader = new XmlBeanDefinitionReader(owner);
        SpringXmlResources.record(reader, new FileSystemResource(file));
        SpringXmlResources.record(reader, new FileSystemResource(file));
        SpringXmlResources.record(reader, new UrlResource("https://example.invalid/settings.xml"));
        SpringXmlResources.record(new Object(), new Object());
        assertEquals(Set.of(file), SpringXmlResources.loadedFiles());
        assertEquals(List.of(owner), SpringXmlResources.owners(file, contextsAsObjects()));
    }

    @Test void reservedKindsKeepTheirMeaningEvenWhenLoadedBySpring() throws Exception {
        Map<String, ChangeKind> names = Map.of("logback.xml", ChangeKind.LOGGING_CONFIG,
                "sample-items.xml", ChangeKind.CODEGEN_XML, "sample-beans.xml", ChangeKind.CODEGEN_XML,
                "sample-backoffice-config.xml", ChangeKind.BACKOFFICE_CONFIG);
        for (var entry : names.entrySet()) {
            Path file = empty(entry.getKey());
            context(file, false);
            assertEquals(entry.getValue(), ChangeKind.of(file));
        }
    }

    @Test void overflowAndExplicitScansIncludeLoadedFilesButNotTheirNeighbours() throws Exception {
        Path loaded = empty("spring-mvc-config.xml");
        Path unrelated = empty("unrelated.xml");
        context(loaded, false);
        FileWatcher watcher = new FileWatcher(platform(), AgentConfig.parse(null));
        try {
            Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
            watcher.enqueueExistingFiles(tmp, "example", "resources", pending);
            assertTrue(pending.containsKey(loaded));
            assertFalse(pending.containsKey(unrelated));
            watcher.registerRecursive(tmp, "example", "resources");
            pending.clear();
            watcher.scanWatchedDirectories(pending, 0);
            assertTrue(pending.containsKey(loaded));
            assertFalse(pending.containsKey(unrelated));
        } finally { watcher.stopWatching(); }
    }

    @Test void extraRootPollingBaselinesDeduplicatesDetectsDeleteAndHonoursExclusions() throws Exception {
        Path file = empty("external/settings.xml");
        var owner = context(file, false);
        FileWatcher watcher = new FileWatcher(platform(), AgentConfig.parse(null));
        FileWatcher excluded = new FileWatcher(platform(), AgentConfig.parse("excludePatterns=settings.xml"));
        try {
            Map<Path, FileWatcher.PendingEvent> pending = new LinkedHashMap<>();
            watcher.scanLoadedSpringXml(pending);
            excluded.scanLoadedSpringXml(pending);
            assertTrue(pending.isEmpty(), "initial content is already loaded");
            Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
            excluded.scanLoadedSpringXml(pending);
            assertTrue(pending.isEmpty());
            watcher.scanLoadedSpringXml(pending);
            assertEquals(Set.of(file), pending.keySet());
            assertEquals(ChangeEvent.Type.MODIFIED, pending.get(file).type());
            pending.clear(); watcher.scanLoadedSpringXml(pending); assertTrue(pending.isEmpty());
            Files.delete(file); watcher.scanLoadedSpringXml(pending);
            assertEquals(ChangeEvent.Type.DELETED, pending.get(file).type());
            pending.clear(); owner.close(); empty("external/settings.xml");
            watcher.scanLoadedSpringXml(pending); assertTrue(pending.isEmpty());
        } finally { watcher.stopWatching(); excluded.stopWatching(); }
    }

    @Test void extraRootPollingRespectsModuleExclusion() throws Exception {
        Path file = empty("settings.xml"); context(file, false);
        FileWatcher watcher = new FileWatcher(new TestPlatform() {
            @Override public boolean shouldWatch(String module) { return false; }
        }, AgentConfig.parse(null));
        try {
            var pending = new LinkedHashMap<Path, FileWatcher.PendingEvent>();
            watcher.scanLoadedSpringXml(pending);
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));
            watcher.scanLoadedSpringXml(pending);
            assertTrue(pending.isEmpty());
        } finally { watcher.stopWatching(); }
    }

    @Test void aSharedLoadedResourceUpdatesEveryOwnerAndKeepsOtherContextUntouched() throws Exception {
        Path file = empty("shared.xml");
        String template = "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + "<bean id='value' class='" + Value.class.getName() + "'>"
                + "<property name='text' value='%s'/></bean></beans>";
        Files.writeString(file, template.formatted("before"));
        var first = context(file, false);
        var second = context(file, true);
        Path otherFile = empty("other/shared.xml");
        Files.writeString(otherFile, template.formatted("other"));
        var other = context(otherFile, false);
        Value heldFirst = first.getBean(Value.class);
        Value heldSecond = second.getBean(Value.class);
        Files.writeString(file, template.formatted("after"));
        new SpringXmlReloader(platform()).reloadLoaded(file);
        assertEquals("after", heldFirst.text);
        assertEquals("after", heldSecond.text);
        assertEquals("other", other.getBean(Value.class).text);
        assertSame(heldFirst, first.getBean(Value.class));
        assertSame(heldSecond, second.getBean(Value.class));
    }

    @Test void requestedScanFindsExtraRootEditsAndLaterContexts() throws Exception {
        FileWatcher watcher = new FileWatcher(platform(), AgentConfig.parse(null));
        try {
            var pending = new LinkedHashMap<Path, FileWatcher.PendingEvent>();
            assertEquals(0, watcher.scanWatchedDirectories(pending, 0));
            Path file = empty("late/settings.xml");
            context(file, false);
            assertEquals(0, watcher.scanWatchedDirectories(pending, 0));
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));
            assertEquals(1, watcher.scanWatchedDirectories(pending, 0));
            assertEquals(Set.of(file), pending.keySet());
        } finally { watcher.stopWatching(); }
    }

    public static class Value {
        public String text;
        public void setText(String text) { this.text = text; }
    }

    private List<Object> contextsAsObjects() { return new ArrayList<>(contexts); }
    private static class TestPlatform implements PlatformContext {
        public Platform getPlatformId() { return Platform.GENERIC; }
        public void initialize() { }
        public Map<String, List<Path>> getClassOutputDirs() { return Map.of(); }
        public Map<String, List<Path>> getSourceDirs() { return Map.of(); }
        public Map<String, List<Path>> getResourceDirs() { return Map.of(); }
        public String resolveClasspath() { return ""; }
        public String resolveClassName(Path file) { return null; }
        public Path resolveOutputDir(Path file) { return null; }
        public Object getApplicationContext() { return ApplicationContextHolder.getApplicationContext(); }
    }
}
