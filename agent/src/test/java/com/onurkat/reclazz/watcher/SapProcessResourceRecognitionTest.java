/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.watcher;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;
import de.hybris.platform.processengine.definition.ProcessDefinitionResource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class SapProcessResourceRecognitionTest {
    @TempDir Path tmp;
    private final List<GenericApplicationContext> contexts = new ArrayList<>();
    @BeforeEach void reset() { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    @AfterEach void close() { contexts.forEach(GenericApplicationContext::close); ApplicationContextHolder.clear(); RestartLedger.clear(); }

    private Path file(String name) throws Exception {
        Path p = tmp.resolve(name); Files.createDirectories(p.getParent());
        Files.writeString(p, "<process name='example'/>"); return p;
    }
    private GenericApplicationContext register(Path path) {
        var context = new GenericApplicationContext(); contexts.add(context);
        var bean = new ProcessDefinitionResource(); bean.setResource(new FileSystemResource(path));
        context.getBeanFactory().registerSingleton("process", bean);
        context.refresh(); ApplicationContextHolder.register(context); return context;
    }

    @Test void onlyRegisteredExactPathIsRecognized() throws Exception {
        Path registered = file("one/flow.xml"), unrelated = file("two/flow.xml");
        register(registered);
        assertEquals("SAP_PROCESS_XML", ChangeKind.of(registered).name());
        assertEquals("SAP_PROCESS_XML", ChangeKind.of(registered.getParent().resolve("../one/flow.xml")).name());
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of("flow.xml"));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(unrelated));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(file("unregistered-process.xml")));
    }

    @Test void closedLazyRemoteAndNonResourceBeansAreNotClaimed() throws Exception {
        Path closed = file("closed.xml"), lazy = file("lazy.xml"), duck = file("duck.xml");
        var ctx = register(closed); ctx.close();
        var other = new GenericApplicationContext(); contexts.add(other);
        other.registerBean("lazy", ProcessDefinitionResource.class, () -> { fail("discovery created a lazy bean"); return null; },
                definition -> definition.setLazyInit(true));
        var remote = new ProcessDefinitionResource(); remote.setResource(new UrlResource("https://example.invalid/flow.xml"));
        other.getBeanFactory().registerSingleton("remote", remote);
        other.getBeanFactory().registerSingleton("duck", new Duck(new FileSystemResource(duck)));
        other.refresh(); ApplicationContextHolder.register(other);
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(closed));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(lazy));
        assertEquals(ChangeKind.UNKNOWN, ChangeKind.of(duck));
    }

    @Test void registeredSpringSuffixIsNotParsedAsBeanXmlButReservedKindsRemainReserved() throws Exception {
        Path process = file("flow-spring.xml"); register(process);
        assertEquals("SAP_PROCESS_XML", ChangeKind.of(process).name());
        for (String name : List.of("log4j2.xml", "example-items.xml", "example-backoffice-config.xml")) {
            Path p = file(name); register(p);
            assertEquals(ChangeKind.of(name), ChangeKind.of(p));
        }
    }

    @Test void nativeOverflowAndRequestedScansIncludeOnlyRegisteredFile() throws Exception {
        Path process = file("native/flow.xml"), other = file("native/other.xml"); register(process);
        var watcher = new FileWatcher(new TestPlatform(), AgentConfig.parse(null));
        try {
            var pending = new LinkedHashMap<Path, FileWatcher.PendingEvent>();
            watcher.enqueueExistingFiles(process.getParent(), "example", "resources", pending);
            assertEquals(Set.of(process), pending.keySet());
            watcher.registerRecursive(process.getParent(), "example", "resources");
            pending.clear(); watcher.scanWatchedDirectories(pending, 0);
            assertTrue(pending.containsKey(process)); assertFalse(pending.containsKey(other));
            pending.clear(); watcher.scanLoadedSpringXml(pending);
            assertTrue(pending.isEmpty(), "native root must not be polled twice");
        } finally { watcher.stopWatching(); }
    }

    @Test void outsideRootsBaselinesDetectsEditDeleteRecreateAndStopsAfterContextCloses() throws Exception {
        Path process = file("extra/flow.xml"); var ctx = register(process);
        var watcher = new FileWatcher(new TestPlatform(), AgentConfig.parse(null));
        try {
            var pending = new LinkedHashMap<Path, FileWatcher.PendingEvent>();
            watcher.scanLoadedSpringXml(pending); assertTrue(pending.isEmpty());
            Files.setLastModifiedTime(process, FileTime.fromMillis(Files.getLastModifiedTime(process).toMillis()+5000));
            assertEquals(1, watcher.scanWatchedDirectories(pending, 0));
            assertEquals(ChangeEvent.Type.MODIFIED, pending.get(process).type());
            assertEquals("sap-process-xml", pending.get(process).sourceRoot());
            pending.clear(); watcher.scanLoadedSpringXml(pending); assertTrue(pending.isEmpty());
            Files.delete(process); watcher.scanLoadedSpringXml(pending);
            assertEquals(ChangeEvent.Type.DELETED, pending.get(process).type());
            pending.clear(); file("extra/flow.xml"); watcher.scanLoadedSpringXml(pending);
            assertEquals(ChangeEvent.Type.CREATED, pending.get(process).type());
            pending.clear(); ctx.close(); Files.delete(process); watcher.scanLoadedSpringXml(pending);
            assertTrue(pending.isEmpty());
        } finally { watcher.stopWatching(); }
    }

    @Test void fileAndModuleExclusionsStillApply() throws Exception {
        Path process = file("flow.xml"); register(process);
        var excluded = new FileWatcher(new TestPlatform(), AgentConfig.parse("excludePatterns=flow.xml"));
        var module = new FileWatcher(new TestPlatform() {
            @Override public boolean shouldWatch(String name) { return false; }
        }, AgentConfig.parse(null));
        try {
            var pending = new LinkedHashMap<Path, FileWatcher.PendingEvent>();
            excluded.scanLoadedSpringXml(pending); module.scanLoadedSpringXml(pending);
            Files.setLastModifiedTime(process, FileTime.fromMillis(Files.getLastModifiedTime(process).toMillis()+5000));
            excluded.scanLoadedSpringXml(pending); module.scanLoadedSpringXml(pending);
            assertTrue(pending.isEmpty());
        } finally { excluded.stopWatching(); module.stopWatching(); }
    }

    @Test void dispatcherReportsUnappliedWarningWithoutSuccessForEveryEventType() throws Exception {
        Path process = file("flow.xml"), other = file("other.xml"); register(process);
        Class<?> agent = Class.forName("com.onurkat.reclazz.agent.ReclazzAgent");
        var handler = Arrays.stream(agent.getDeclaredMethods()).filter(m -> m.getName().equals("handleChange")).findFirst().orElseThrow();
        handler.setAccessible(true);
        List<String> events = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> events.add(level + ":" + message);
        StatusReporter.addListener(listener);
        try {
            for (var type : ChangeEvent.Type.values()) {
                events.clear();
                handler.invoke(null, new ChangeEvent(process, type, "example", "resources"), null, null, null, null, null, AgentConfig.parse(null));
                assertEquals(1, events.size(), events.toString());
                assertTrue(events.get(0).startsWith("WARN:"), events.toString());
                assertTrue(events.get(0).contains("not applied") && events.get(0).contains("restart required"), events.toString());
                assertTrue(events.get(0).contains(type.name()), events.toString());
                assertEquals(1, RestartLedger.size(), "repeated resource edits remain one outstanding concern");
                assertTrue(String.join("\n", RestartLedger.digest()).contains("flow.xml: SAP process definition resource changed but was not applied"));
            }
            events.clear();
            handler.invoke(null, new ChangeEvent(other, ChangeEvent.Type.MODIFIED, "example", "resources"), null, null, null, null, null, AgentConfig.parse(null));
            assertTrue(events.isEmpty(), events.toString());
        } finally { StatusReporter.removeListener(listener); }
    }

    public record Duck(org.springframework.core.io.Resource resource) {
        public org.springframework.core.io.Resource getResource() { return resource; }
    }
    private static class TestPlatform implements PlatformContext {
        public Platform getPlatformId() { return Platform.GENERIC; }
        public void initialize() { }
        public Map<String,List<Path>> getClassOutputDirs() { return Map.of(); }
        public Map<String,List<Path>> getSourceDirs() { return Map.of(); }
        public Map<String,List<Path>> getResourceDirs() { return Map.of(); }
        public String resolveClasspath() { return ""; }
        public String resolveClassName(Path p) { return null; }
        public Path resolveOutputDir(Path p) { return null; }
        public Object getApplicationContext() { return ApplicationContextHolder.getApplicationContext(); }
    }
}
