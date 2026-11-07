/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.*;
import com.onurkat.reclazz.watcher.ChangeKind;
import de.hybris.platform.processengine.definition.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SapProcessDefinitionReloadTest {
    @TempDir Path temporary;
    GenericApplicationContext context;
    Path file;
    XMLProcessDefinitionsReader reader;
    ProcessDefinitionsProvider provider;
    ProcessDefinitionsCache cache;
    DefaultProcessDefinitionFactory factory;
    final List<String> messages = new ArrayList<>();
    final StatusReporter.StatusListener listener = (level, message) -> messages.add(level + ":" + message);

    @BeforeEach void setup() throws Exception {
        ApplicationContextHolder.clear(); RestartLedger.clear(); StatusReporter.addListener(listener);
        file = temporary.resolve("flow.xml"); save("exampleA", "v1");
        context = new GenericApplicationContext();
        reader = new XMLProcessDefinitionsReader(); provider = new ProcessDefinitionsProvider();
        provider.applicationContext = context; provider.reader = reader;
        cache = new ProcessDefinitionsCache(); cache.processDefinitionsProvider = provider;
        factory = new DefaultProcessDefinitionFactory(); factory.definitionsCache = cache; factory.xmlDefinitionsReader = reader;
        context.getBeanFactory().registerSingleton("factory", factory);
        register(file, "resource"); context.refresh(); ApplicationContextHolder.register(context);
    }
    void register(Path path, String name) {
        var holder = new ProcessDefinitionResource(); holder.setResource(new FileSystemResource(path));
        context.getBeanFactory().registerSingleton(name, holder);
    }
    void save(String name, String graph) throws Exception {
        Files.writeString(file, "<process name='" + name + "' start='" + graph + "'/>");
    }
    ProcessDefinition active(String name) { return cache.definitions.get(new ProcessDefinitionId(name)); }
    PlatformChangeHandler.Outcome apply() { return apply("MODIFIED"); }
    PlatformChangeHandler.Outcome apply(String event) {
        return new SapProcessDefinitionReloader().handle(new PlatformChangeHandler.Change(
                Capability.PROCESS_RELOAD, file, event, "example", "resources"));
    }
    void loaded() throws Exception { cache.initialise(true); messages.clear(); }
    @AfterEach void cleanup() {
        context.close(); ApplicationContextHolder.clear(); SapProcessDefinitions.discard(cache);
        StatusReporter.removeListener(listener); RestartLedger.clear();
    }

    @Test void newProcessFollowsEditedGraph() throws Exception {
        loaded(); var old = active("exampleA"); save("exampleA", "v2");
        assertEquals(PlatformChangeHandler.Outcome.HANDLED, apply());
        assertEquals("v2", active("exampleA").graph()); assertSame(old, cache.definitions.get(old.getId()));
        assertEquals(0, RestartLedger.size());
        assertTrue(messages.stream().anyMatch(m -> m.contains("processes already started keep the version")));
    }
    @Test void brokenXmlKeepsActiveDefinition() throws Exception {
        loaded(); var old = active("exampleA"); Files.writeString(file, "<process>");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(old, active("exampleA"));
        assertEquals(1, RestartLedger.size()); assertTrue(messages.toString().contains("SAXParseException"));
    }
    @Test void dbBackedDefinitionIsRefused() throws Exception {
        loaded(); var old = active("exampleA"); provider.dynamic = true; save("exampleA", "v2");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(old, active("exampleA"));
    }
    @Test void providerFailureIsRefused() throws Exception {
        loaded(); var old = active("exampleA"); provider.failProvider = true; save("exampleA", "v2");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(old, active("exampleA"));
    }
    @Test void renamedProcessIsRefused() throws Exception {
        loaded(); var old = active("exampleA"); save("exampleB", "v2");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(old, active("exampleA"));
        assertNull(active("exampleB"));
    }
    @Test void duplicateNameIsRefused() throws Exception {
        Path duplicate = temporary.resolve("duplicate.xml");
        Files.copy(file, duplicate); register(duplicate, "duplicate");
        loaded(); var old = active("exampleA");
        save("exampleA", "v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(old, active("exampleA"));
        assertTrue(messages.toString().contains("multiple registered resources define process exampleA"));
    }
    @Test void malformedSiblingDoesNotBlockValidSave() throws Exception {
        Path sibling = temporary.resolve("sibling.xml");
        Files.writeString(sibling, "<process name='exampleB' start='v1'/>"); register(sibling, "sibling");
        loaded(); var oldB = active("exampleB");
        Files.writeString(sibling, "<process>");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, new SapProcessDefinitionReloader().handle(
                new PlatformChangeHandler.Change(Capability.PROCESS_RELOAD, sibling, "MODIFIED", "example", "resources")));
        assertSame(oldB, active("exampleB"));
        int parses = reader.parses;
        save("exampleA", "v2");
        assertEquals(PlatformChangeHandler.Outcome.HANDLED, apply());
        assertEquals("v2", active("exampleA").graph()); assertSame(oldB, active("exampleB"));
        assertEquals(parses + 1, reader.parses, "A save must parse only its own resource");
    }
    @Test void customFactoryIsRefused() throws Exception {
        context.getDefaultListableBeanFactory().destroySingleton("factory");
        context.getBeanFactory().registerSingleton("factory", new DefaultProcessDefinitionFactory() { });
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertEquals(0, reader.parses);
    }
    @Test void unchangedContentIsNotRepublished() throws Exception {
        loaded(); int publications = cache.publications;
        assertEquals(PlatformChangeHandler.Outcome.IGNORED, apply()); assertEquals(publications, cache.publications);
    }
    @Test void createdAndDeletedKeepDiagnostic() {
        for (String event : List.of("CREATED", "DELETED")) {
            assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply(event));
            assertTrue(messages.toString().contains(event + ": not applied; restart required"));
        }
        assertEquals(0, reader.parses);
    }
    @Test void repeatedSavesApplyLatest() throws Exception {
        loaded(); var original = active("exampleA");
        for (String graph : List.of("v2", "v3", "v1")) {
            save("exampleA", graph); assertEquals(PlatformChangeHandler.Outcome.HANDLED, apply());
            assertEquals(graph, active("exampleA").graph());
        }
        assertEquals(original.getVersion(), active("exampleA").getVersion());
    }
    @Test void withoutModuleTheDiagnosticIsUnchanged() {
        new AdviceOnlyChangeHandler().handle(new PlatformChangeHandler.Change(Capability.PROCESS_RELOAD,
                file, "MODIFIED", "example", "resources"));
        assertEquals(List.of("WARN:SAP process definition flow.xml MODIFIED: not applied; restart required. "
                + "Process definition hot reload is unsupported; no running process was changed or replayed."), messages);
    }
    @Test void refusedSaveRecoversOnlyThroughRetainedAssociation() throws Exception {
        loaded(); save("exampleB", "v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply());
        save("exampleA", "v2"); assertEquals(PlatformChangeHandler.Outcome.HANDLED, apply());
        SapProcessDefinitions.discard(cache); save("exampleA", "v1");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply());
    }
    @Test void discoveryWithoutModuleNeverParsesOrCreatesActionBeans() {
        context.registerBean("lazyAction", Object.class, () -> { fail("Lazy action created"); return null; },
                d -> d.setLazyInit(true));
        assertEquals("SAP_PROCESS_XML", ChangeKind.of(file).name());
        assertTrue(SapProcessResources.registeredFiles().contains(file));
        assertEquals(0, reader.parses); assertEquals(0, provider.initialCalls);
        assertFalse(context.getBeanFactory().containsSingleton("lazyAction"));
    }
    @Test void baselineDoesNotSurviveClosedOrReplacedContextOwnership() throws Exception {
        loaded(); var old = active("exampleA"); context.close(); save("exampleA", "v2");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(old, active("exampleA"));
    }
    @Test void replacedCacheDoesNotInheritAssociation() throws Exception {
        loaded(); var replacement = new ProcessDefinitionsCache(); replacement.processDefinitionsProvider = provider;
        replacement.definitions = new HashMap<>(cache.definitions); factory.definitionsCache = replacement;
        save("exampleA", "v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply());
        assertEquals("v1", replacement.definitions.get(new ProcessDefinitionId("exampleA")).graph());
    }
    @Test void replacedContextRequiresItsOwnObservedStartup() throws Exception {
        loaded(); context.close(); context = new GenericApplicationContext();
        provider.applicationContext = context;
        context.getBeanFactory().registerSingleton("factory", factory); register(file, "resource");
        context.refresh(); ApplicationContextHolder.register(context); save("exampleA", "v2");
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply());
        cache.initialise(true); save("exampleA", "v3");
        assertEquals(PlatformChangeHandler.Outcome.HANDLED, apply()); assertEquals("v3", active("exampleA").graph());
    }
    @Test void replacementDuringParsingCannotPublishIntoNewCache() throws Exception {
        loaded(); var original = active("exampleA");
        var replacement = new ProcessDefinitionsCache(); replacement.processDefinitionsProvider = provider;
        replacement.definitions = new HashMap<>(cache.definitions);
        reader.afterParse = () -> factory.definitionsCache = replacement;
        save("exampleA", "v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply());
        assertSame(original, replacement.definitions.get(new ProcessDefinitionId("exampleA")));
        assertSame(original, active("exampleA"));
    }
    @Test void parsingDoesNotHoldCacheLockNeededByExistingProcessReaders() throws Exception {
        loaded(); var original = active("exampleA");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            reader.afterParse = () -> {
                var read = executor.submit(() -> {
                    if (!cache.definitionsLock.readLock().tryLock(1, java.util.concurrent.TimeUnit.SECONDS)) return false;
                    try { return active("exampleA") == original; }
                    finally { cache.definitionsLock.readLock().unlock(); }
                });
                try { assertTrue(read.get(2, java.util.concurrent.TimeUnit.SECONDS), "Native readers must not wait for parser/bean creation"); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            };
            save("exampleA", "v2"); assertEquals(PlatformChangeHandler.Outcome.HANDLED, apply());
        } finally { executor.shutdownNow(); }
    }
    @Test void nativeParsingUsesOwnerLoaderAndRestoresWatcherLoader() throws Exception {
        loaded(); save("exampleA", "v2");
        ClassLoader ownerLoader = context.getBeanFactory().getBeanClassLoader();
        reader.afterParse = () -> assertSame(ownerLoader, Thread.currentThread().getContextClassLoader(),
                "Native JAXB and application bean resolution need the owner's loader");
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ClassLoader watcherLoader = new ClassLoader(null) { };
        thread.setContextClassLoader(watcherLoader);
        try {
            assertEquals(PlatformChangeHandler.Outcome.HANDLED, apply());
            assertEquals("v2", active("exampleA").graph());
            assertSame(watcherLoader, thread.getContextClassLoader());
        } finally { thread.setContextClassLoader(previous); }
    }
    @Test void nativeProviderFailureRestoresWatcherLoader() throws Exception {
        loaded(); var original = active("exampleA"); save("exampleA", "v2");
        ClassLoader ownerLoader = context.getBeanFactory().getBeanClassLoader();
        reader.afterParse = () -> assertSame(ownerLoader, Thread.currentThread().getContextClassLoader());
        provider.failProvider = true;
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ClassLoader watcherLoader = new ClassLoader(null) { };
        thread.setContextClassLoader(watcherLoader);
        try {
            assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply());
            assertSame(original, active("exampleA"));
            assertTrue(messages.toString().contains("example provider failure"), "The native provider must actually be reached");
            assertSame(watcherLoader, thread.getContextClassLoader());
        } finally { thread.setContextClassLoader(previous); }
    }
    @Test void activeDefinitionReplacedElsewhereIsRefused() throws Exception {
        loaded(); save("exampleA", "programmatic"); var other = reader.from(new FileSystemResource(file));
        factory.add(other); save("exampleA", "v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply());
        assertSame(other, active("exampleA")); assertTrue(SapProcessDefinitions.observed(cache, provider, context).isEmpty());
    }
    @Test void captureNeverCallsProviderOrInitialisesCache() throws Exception {
        save("exampleA", "v2"); assertEquals(PlatformChangeHandler.Outcome.IGNORED, apply());
        assertNull(cache.definitions); assertEquals(0, provider.initialCalls); assertEquals(0, provider.latestCalls);
        assertEquals(0, reader.parses); assertTrue(messages.toString().contains("will be read when they first load"));
    }
    @Test void copiedUnrelatedDefinitionCannotAuthorizeLaterOverwrite() throws Exception {
        cache.initialise(false); // Simulates startup before the observer was installed.
        save("exampleB", "v1"); var b = reader.from(new FileSystemResource(file)); factory.add(b);
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(b, active("exampleB"));
        save("exampleB", "v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(b, active("exampleB"));
    }
    @Test void firstSeenAfterRenameDoesNotOverwriteAnotherActiveProcess() throws Exception {
        loaded(); save("exampleB", "v1"); var b = reader.from(new FileSystemResource(file)); factory.add(b);
        assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(b, active("exampleB"));
        save("exampleB", "v2"); assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(b, active("exampleB"));
    }
    @Test void lateAttachRefusesEverySave() throws Exception {
        cache.initialise(false); var old = active("exampleA");
        for (String graph : List.of("v2", "v1", "v3")) {
            save("exampleA", graph); assertEquals(PlatformChangeHandler.Outcome.ADVICE, apply()); assertSame(old, active("exampleA"));
        }
    }
}
