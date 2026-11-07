/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SolrReindexerTest {
    SolrNativeFixture fixture;
    SolrReindexer reindexer;
    List<String> messages;
    StatusReporter.StatusListener listener;
    @BeforeEach void setup() {
        fixture = new SolrNativeFixture();
        reindexer = new SolrReindexer(() -> List.of(fixture.context));
        messages = Collections.synchronizedList(new ArrayList<>());
        listener = (level, message) -> messages.add(level + ":" + message);
        StatusReporter.addListener(listener);
    }
    @AfterEach void cleanup() {
        reindexer.close(); fixture.close(); StatusReporter.removeListener(listener);
    }
    void run() { reindexer.run(Set.of(SolrNativeFixture.Provider.class)); }
    void refused(String reason) {
        run(); assertTrue(fixture.executions.isEmpty(), "an unsafe target must not execute");
        assertTrue(messages.toString().contains(reason), messages.toString());
        assertTrue(messages.stream().noneMatch(m -> m.startsWith("WARN:")));
    }
    @Test void discoveryNeverCallsConfigServiceCacheConverterOrGetBean() throws Exception {
        fixture.failConfigAccess = true;
        fixture.beanFactory.metadataOnly = true;
        try {
            for (int fallback = 0; fallback < 3; fallback++) {
                fixture.type.properties.get("selected").provider = fallback == 0 ? "providerAlias" : " ";
                fixture.type.defaultProvider = fallback == 1 ? "providerAlias" : null;
                fixture.selector.defaultId = fallback == 2 ? "providerAlias" : "other";
                var discovery = SolrReindexer.discover(fixture.context, Set.of(SolrNativeFixture.Provider.class), 1);
                assertEquals(Set.of("selected"), discovery.types().get(0).selectedProperties());
            }
        } finally { fixture.beanFactory.metadataOnly = false; }
        assertEquals(0, fixture.configReads); assertEquals(0, fixture.cacheReads);
        assertEquals(0, SolrNativeFixture.Provider.constructed.get());
    }
    public static class LegacyTypeProvider extends SolrNativeFixture.Provider { }
    @Test void coldCacheLegacyTypeProviderIsNotConstructedDuringDiscovery() throws Exception {
        for (String scope : List.of("singleton", "prototype")) {
            var definition = new org.springframework.beans.factory.support.RootBeanDefinition(LegacyTypeProvider.class);
            definition.setScope(scope); definition.setLazyInit(true);
            fixture.context.registerBeanDefinition("legacy", definition);
            fixture.type.fieldsProvider = "legacy"; fixture.loadLegacyInConfig = true;
            int before = SolrNativeFixture.Provider.constructed.get();
            var discovery = SolrReindexer.discover(fixture.context, Set.of(SolrNativeFixture.Provider.class), 1);
            assertTrue(discovery.types().get(0).whole());
            assertEquals(before, SolrNativeFixture.Provider.constructed.get(), scope);
            run();
            assertTrue(SolrNativeFixture.Provider.constructed.get() > before, "construction is explicit native preparation/indexing only");
            assertFalse(fixture.executions.isEmpty(), messages.toString());
            fixture.context.getDefaultListableBeanFactory().destroySingleton("legacy");
            fixture.context.removeBeanDefinition("legacy");
        }
    }
    @Test void phaseBMismatchWithDiscoveryRefuses() {
        fixture.beforeConfigRead = () -> fixture.type.properties.get("selected").provider = "other";
        refused("configuration-changed-since-discovery");
        assertEquals(0, fixture.strategies); assertEquals(0, fixture.clients);
    }
    @Test void workerRunsPreparationInOwningTenantAndRestoresSession() throws Exception {
        Thread applying = Thread.currentThread();
        var prior = new SolrNativeFixture.Session("caller-language"); prior.activate();
        fixture.beforeConfigRead = () -> {
            assertSame(fixture.tenant, SolrNativeFixture.TENANT.get());
            assertNotSame(prior, SolrNativeFixture.SESSION.get());
            assertEquals("default", SolrNativeFixture.SESSION.get().language);
        };
        run(); assertSame(prior, SolrNativeFixture.SESSION.get());
        fixture.throwExecution = true; run(); assertSame(prior, SolrNativeFixture.SESSION.get());
        fixture.throwExecution = false; fixture.executions.clear(); reindexer.enqueue(SolrNativeFixture.Provider.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((fixture.executions.isEmpty() || fixture.sessions.created != fixture.sessions.closed) && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(1, fixture.executions.size(), messages.toString());
        assertNotSame(applying, fixture.configurationThread);
        assertEquals("Reclazz-Solr", fixture.configurationThread.getName());
        assertSame(prior, SolrNativeFixture.SESSION.get());
        assertEquals(fixture.sessions.created, fixture.sessions.closed);
    }
    @Test void unusedProviderDoesNotLoadNativeConfiguration() {
        fixture.failConfigAccess = true;
        reindexer.run(Set.of(String.class));
        assertEquals(0, fixture.configReads); assertEquals(0, fixture.cacheReads);
        assertEquals(0, fixture.strategies); assertEquals(0, fixture.clients);
        assertTrue(messages.toString().contains("provider-unused"));
        assertNull(SolrNativeFixture.SESSION.get());
    }
    @Test void propertyProviderRunsOnePartialUpdateWithOnlyAffectedProperties() {
        run();
        assertEquals(1, fixture.executions.size(), messages.toString());
        var operation = fixture.executions.get(0);
        assertEquals("PARTIAL_UPDATE", operation.operation());
        assertEquals(List.of(fixture.type.properties.get("selected")), operation.properties());
        assertEquals(List.of("11", "22"), operation.pks().stream().map(Object::toString).toList());
        assertEquals(Map.of("11", "v2", "22", "v2"), fixture.documents);
        assertFalse(fixture.documents.containsKey("33"), "not-yet-indexed items must remain absent");
        assertTrue(messages.toString().contains("FINISHED partial properties=1 documents=2"));
    }
    @Test void nativeConfigurationCopiesDoNotLookLikeReplacement() {
        fixture.copyConfigurations = true;
        run();
        assertEquals(1, fixture.executions.size(), messages.toString());
        assertEquals(Map.of("11", "v2", "22", "v2"), fixture.documents);
    }
    @Test void cachedConfigurationReplacementDuringPkReadRefuses() {
        fixture.beforeQuery = () -> fixture.cachedConfig = new SolrNativeFixture.Config(fixture.config);
        refused("config-replaced");
    }
    @Test void nativeTypeMapKeyNeedNotBeTheTypeIdentifier() {
        fixture.config.index.types.clear();
        fixture.config.index.types.put("NativeModelCode", fixture.type);
        run();
        assertEquals(1, fixture.executions.size(), messages.toString());
        assertEquals(Map.of("11", "v2", "22", "v2"), fixture.documents);
    }
    @Test void typeResolverRunsWholeTypeUpdateWithExplicitPks() {
        fixture.type.fieldsProvider = "providerAlias";
        run();
        assertEquals(1, fixture.executions.size(), messages.toString());
        assertEquals("UPDATE", fixture.executions.get(0).operation());
        assertTrue(fixture.executions.get(0).properties().isEmpty());
        assertEquals(2, fixture.executions.get(0).pks().size());
    }
    @Test void nullPkListIsNeverPassedAndFullOperationIsNeverCalled() {
        run(); fixture.type.fieldsProvider = "providerAlias"; run();
        assertEquals(2, fixture.executions.size(), messages.toString());
        for (var operation : fixture.executions) {
            assertNotNull(operation.pks()); assertFalse(operation.pks().isEmpty());
            assertNotEquals("FULL", operation.operation());
        }
    }
    @Test void multipleChangedProvidersOfOneTypeShareOneOperation() {
        reindexer.run(Set.of(SolrNativeFixture.Provider.class, SolrNativeFixture.Other.class));
        assertEquals(1, fixture.executions.size(), messages.toString());
        assertEquals(2, fixture.executions.get(0).properties().size());
    }
    @Test void typeDefaultProviderFallbackIsResolvedLikeNativeSelector() {
        fixture.type.properties.get("selected").provider = " ";
        fixture.type.defaultProvider = "providerAlias";
        run(); assertEquals(1, fixture.executions.size(), messages.toString());
    }
    @Test void globalDefaultProviderFallbackIsResolvedLikeNativeSelector() {
        fixture.type.properties.get("selected").provider = null;
        fixture.selector.defaultId = "providerAlias";
        run(); assertEquals(1, fixture.executions.size(), messages.toString());
    }
    @Test void providerUsedNowhereDoesNothing() {
        reindexer.run(Set.of(String.class));
        assertTrue(fixture.executions.isEmpty()); assertEquals(0, fixture.clients);
    }
    @Test void mappingNeverInstantiatesBeansAndAliasesDeduplicate() {
        fixture.type.properties.put("alsoSelected", new SolrNativeFixture.Property("alsoSelected", "provider"));
        run(); assertEquals(0, fixture.constructedAtClient);
        assertEquals(2, fixture.executions.get(0).properties().size());
        assertEquals(1, fixture.executions.size());
        assertTrue(SolrNativeFixture.Provider.constructed.get() > 0, "the native indexing fixture actually resolves its prototype");
    }
    @Test void customSelectorOrUntypeableBeanRefusesWithAdvice() {
        fixture.context.getDefaultListableBeanFactory().destroySingleton("valueProviderSelectionStrategy");
        fixture.register("valueProviderSelectionStrategy", new SolrNativeFixture.Selector());
        refused("custom-selector"); assertEquals(0, fixture.clients);
    }
    @Test void unresolvedBeanRefusesBeforeAnyRead() {
        fixture.type.properties.get("unrelated").provider = "missing";
        refused("unresolved-provider"); assertEquals(0, fixture.clients);
    }
    @Test void factoryBeansAreNotInstantiatedToDiscoverProviderTypes() {
        fixture.context.removeBeanDefinition("provider");
        fixture.context.registerBeanDefinition("provider", new org.springframework.beans.factory.support.RootBeanDefinition(ProviderFactory.class));
        refused("factory-or-proxy-provider"); assertEquals(0, fixture.clients);
        assertEquals(0, SolrNativeFixture.Provider.constructed.get());
    }
    public static class ProviderFactory implements org.springframework.beans.factory.FactoryBean<SolrNativeFixture.Provider> {
        public SolrNativeFixture.Provider getObject() { throw new AssertionError("discovery instantiated FactoryBean"); }
        public Class<?> getObjectType() { return SolrNativeFixture.Provider.class; }
    }
    @Test void customScopeRefusesBeforeClientCreation() {
        var bd = new org.springframework.beans.factory.support.RootBeanDefinition(SolrNativeFixture.Provider.class);
        bd.setScope("request"); fixture.context.registerBeanDefinition("provider", bd);
        refused("custom-scoped-provider"); assertEquals(0, fixture.clients);
    }
    @Test void factoryMethodRefusesBeforeClientCreation() {
        var bd = new org.springframework.beans.factory.support.RootBeanDefinition(SolrNativeFixture.Provider.class);
        bd.setFactoryMethodName("createProvider"); fixture.context.registerBeanDefinition("provider", bd);
        refused("factory-method-provider"); assertEquals(0, fixture.clients);
    }
    @Test void nonLoopbackEndpointRefuses() {
        fixture.config.solr.endpoints = List.of(new SolrNativeFixture.Endpoint("http://192.0.2.1:8983/solr"));
        refused("non-loopback-endpoint"); assertEquals(0, fixture.clients);
    }
    @Test void cloudWithLoopbackSeedsRefusesBeforeClientCreation() {
        fixture.config.solr.mode = "CLOUD";
        refused("unsupported-server-mode"); assertEquals(0, fixture.clients);
    }
    @Test void emptyEndpointsRefuse() {
        fixture.config.solr.endpoints = List.of(); refused("missing-endpoints"); assertEquals(0, fixture.clients);
    }
    @Test void unknownProviderRefuses() {
        fixture.actualProvider = new SolrNativeFixture.SearchProvider(); refused("custom-search-provider");
        assertEquals(0, fixture.clients);
    }
    @Test void distributedIndexingRefuses() { fixture.config.index.distributed = true; refused("distributed-indexing"); }
    @Test void noActiveIndexRefuses() { fixture.active = null; refused("native-operation-failed"); }
    @Test void pkLimitRefuses() { fixture.client.total = 10001; refused("document-limit-or-index-changed"); }
    @Test void invalidStoredPkRefuses() { fixture.client.invalidPk = true; refused("invalid-stored-pk"); }
    @Test void partialQueryRefuses() { fixture.client.partial = true; refused("partial-query-results"); }
    @Test void readErrorRefuses() { fixture.client.fail = true; refused("native-operation-failed"); }
    @Test void customIndexerStrategyRefuses() { fixture.customStrategy = true; refused("custom-indexer-strategy"); }
    @Test void emptyIndexDoesNotInvokeNativeOperation() {
        fixture.documents.clear(); run(); assertTrue(fixture.executions.isEmpty());
        assertTrue(messages.toString().contains("no stored documents"));
    }
    @Test void pkEnumerationDoesNotUseDivergentSearchReplica() { run(); assertEquals(0, fixture.searched); assertEquals(1, fixture.executions.size()); }
    @Test void runtimeUniqueKeyIsUsedForCursorSort() {
        fixture.key = "syntheticUniqueId"; run(); assertEquals(1, fixture.executions.size(), messages.toString());
    }
    @Test void activeIndexChangesDuringPkReadRefuses() {
        fixture.beforeQuery = () -> fixture.active = "B"; refused("active-index-switched");
        assertEquals(Map.of("11", "v1", "22", "v1"), fixture.documents);
    }
    @Test void activeIndexSwitchBeforeNativeResolutionCannotUseOldPks() {
        fixture.beforeExecute = () -> fixture.active = "B"; run();
        assertEquals(List.of("coreA", "coreA"), fixture.stores, messages.toString());
        assertTrue(fixture.stores.stream().noneMatch("coreB"::equals));
    }
    @Test void sharedPhysicalIndexRefuses() {
        var other = new SolrNativeFixture.Type(); other.id = "otherType";
        fixture.config.index.types.put(other.id, other); refused("shared-physical-index");
        assertEquals(0, fixture.clients);
    }
    @Test void swallowedProviderFailureNeverReportsIndexed() {
        fixture.config.index.ignore = true; fixture.ignoreFailure = true; run();
        assertTrue(messages.toString().contains("FINISHED_UNVERIFIED"));
        assertEquals(Map.of("11", "v1", "22", "v1"), fixture.documents);
        assertTrue(messages.stream().noneMatch(m -> m.contains(": FINISHED ") || m.contains("fields refreshed")));
    }
    @Test void privateDetailsAreRedactedFromOutcome() {
        fixture.throwExecution = true; run();
        for (String secret : List.of("com.example.secret.Provider", "/Users/example/x", "private-value"))
            assertFalse(messages.toString().contains(secret));
        assertTrue(messages.toString().contains("FAILED"));
    }
    @Test void workerRestoresLoaderAndTenantAfterSuccessAndFailure() {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        Object previousTenant = new Object(); SolrNativeFixture.TENANT.set(previousTenant);
        fixture.beforeExecute = () -> {
            assertSame(fixture.tenant, SolrNativeFixture.TENANT.get());
            assertSame(fixture.context.getBeanFactory().getBeanClassLoader(), Thread.currentThread().getContextClassLoader());
        };
        run(); assertSame(before, Thread.currentThread().getContextClassLoader()); assertSame(previousTenant, SolrNativeFixture.TENANT.get());
        fixture.throwExecution = true; run();
        assertSame(before, Thread.currentThread().getContextClassLoader()); assertSame(previousTenant, SolrNativeFixture.TENANT.get());
    }
    @Test void burstOfSavesCoalescesIntoOneOperation() throws Exception {
        for (int i = 0; i < 8; i++) reindexer.enqueue(SolrNativeFixture.Provider.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (messages.stream().noneMatch(m -> m.contains(": FINISHED ")) && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(1, fixture.executions.size(), messages.toString());
        assertTrue(messages.toString().contains("FINISHED"));
    }
    @Test void optionOffIsDefaultAndParserKeepsAdjacentArguments() {
        assertFalse(AgentConfig.parse(null).isAutoSolrReindex());
        var config = AgentConfig.parse("verbose=true,autoSolrReindex=true,debounceMs=123");
        assertTrue(config.isAutoSolrReindex()); assertTrue(config.isVerbose());
        assertEquals(123, config.getDebounceMs()); assertTrue(config.getUnknownKeys().isEmpty());
        assertFalse(AgentConfig.parse("autoSolrReindex=false").isAutoSolrReindex());
    }
    @Test void moduleAdmissionOwnsTheOperation() {
        AtomicInteger calls = new AtomicInteger();
        var modules = new PremiumModules(capability -> new PremiumAdmission.Admission(false, "synthetic refusal"));
        modules.register(() -> new PremiumModule() {
            public Set<Capability> capabilities() { return Set.of(Capability.SOLR_REINDEX); }
            public boolean reindex(Class<?> type) { calls.incrementAndGet(); return true; }
        });
        assertFalse(modules.reindex(SolrNativeFixture.Provider.class)); assertEquals(0, calls.get());
    }
}
