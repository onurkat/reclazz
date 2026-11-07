/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.support.RootBeanDefinition;

/** Synthetic SDK-shaped graph; no Solr server or customer data. */
public final class SolrNativeFixture implements AutoCloseable {
    public static final ThreadLocal<Object> TENANT = new ThreadLocal<>();
    public static final ThreadLocal<Session> SESSION = new ThreadLocal<>();
    public static Object master;
    public final GuardedBeanFactory beanFactory = new GuardedBeanFactory();
    public final GenericApplicationContext context = new GenericApplicationContext(beanFactory);
    public final Sessions sessions = new Sessions();
    public final Config config = new Config();
    public Config cachedConfig = config;
    public boolean copyConfigurations;
    public final Type type = new Type();
    public final Client client = new Client();
    public final Map<String, Index> indexes = new HashMap<>();
    public final List<Execution> executions = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final List<String> stores = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final Map<String, String> documents = new java.util.concurrent.ConcurrentSkipListMap<>();
    public final Tenant tenant = new Tenant(context);
    public final IndexService indexService = new IndexService();
    public final de.hybris.platform.solrfacetsearch.provider.impl.DefaultValueProviderSelectionStrategy selector =
            new de.hybris.platform.solrfacetsearch.provider.impl.DefaultValueProviderSelectionStrategy();
    public final de.hybris.platform.solrfacetsearch.solr.impl.SolrStandaloneSearchProvider provider =
            new de.hybris.platform.solrfacetsearch.solr.impl.SolrStandaloneSearchProvider();
    public Object actualProvider = provider;
    public boolean customStrategy, throwExecution, ignoreFailure;
    public Runnable beforeExecute = () -> { }, beforeQuery = () -> { }, beforeConfigRead = () -> { };
    public boolean failConfigAccess, loadLegacyInConfig;
    public int configReads, cacheReads, strategies;
    public Thread configurationThread;
    public String active = "A", key = "id";
    public int clients, searched, constructedAtClient = -1;

    public SolrNativeFixture() {
        Provider.constructed.set(0);
        config.index.types.put(type.id, type);
        type.properties.put("selected", new Property("selected", "providerAlias"));
        type.properties.put("unrelated", new Property("unrelated", "other"));
        selector.context = context; provider.fixture = this;
        register("facetSearchConfigService", new ConfigService(this));
        register("flexibleSearchService", new Search(this));
        register("sessionService", sessions);
        register("valueProviderSelectionStrategy", selector);
        register("solrSearchProviderFactory", new ProviderFactory(this));
        register("solrIndexService", indexService);
        register("indexerStrategyFactory", new StrategyFactory(this));
        context.registerBeanDefinition("provider", new RootBeanDefinition(Provider.class) {{ setLazyInit(true); setScope("prototype"); }});
        context.registerBeanDefinition("other", new RootBeanDefinition(Other.class) {{ setLazyInit(true); }});
        context.registerAlias("provider", "providerAlias");
        context.refresh(); master = tenant;
        indexes.put("A", new Index("coreA", "A", type));
        indexes.put("B", new Index("coreB", "B", type));
        documents.put("11", "v1"); documents.put("22", "v1");
        client.fixture = this;
    }
    public void register(String name, Object value) { context.getBeanFactory().registerSingleton(name, value); }
    @Override public void close() { context.close(); master = null; TENANT.remove(); SESSION.remove(); }
    public static class Provider {
        public static final AtomicInteger constructed = new AtomicInteger();
        public Provider() { constructed.incrementAndGet(); }
        public String value() { return "v2"; }
    }
    public static class Other { }
    public record Tenant(Object applicationContext) { public Object getApplicationContext() { return applicationContext; } }
    public static class Config {
        public final IndexConfig index;
        public final Solr solr;
        public Config() { index = new IndexConfig(); solr = new Solr(); }
        public Config(Config source) { index = source.index; solr = source.solr; }
        public String getName() { return "synthetic"; }
        public IndexConfig getIndexConfig() { return index; }
        public Solr getSolrConfig() { return solr; }
    }
    public static class Solr {
        public String mode = "STANDALONE";
        public List<Endpoint> endpoints = new ArrayList<>(List.of(new Endpoint("http://localhost:8983/solr")));
        public String getMode() { return mode; }
        public List<Endpoint> getEndpointURLs() { return endpoints; }
    }
    public record Endpoint(String url) { public String getUrl() { return url; } }
    public static class IndexConfig {
        public Map<String, Type> types = new LinkedHashMap<>();
        public boolean distributed, ignore;
        public Map<String, Type> getIndexedTypes() { return types; }
        public boolean isDistributedIndexing() { return distributed; }
        public boolean isIgnoreErrors() { return ignore; }
    }
    public static class Type {
        public String id = "type", fieldsProvider, defaultProvider;
        public Map<String, Property> properties = new LinkedHashMap<>();
        public String getIdentifier() { return id; }
        public Map<String, Property> getIndexedProperties() { return properties; }
        public String getFieldsValuesProvider() { return fieldsProvider; }
        public String getDefaultFieldValueProvider() { return defaultProvider; }
    }
    public static class Property {
        public final String name;
        public String provider;
        public Property(String name, String provider) { this.name = name; this.provider = provider; }
        public String getFieldValueProvider() { return provider; }
        public String getName() { return name; }
    }
    public static class Selector {
        public Object context;
        public String defaultId = "other";
        public Object getApplicationContext() { return context; }
        public String getDefaultValueProviderId() { return defaultId; }
        public String resolveValueProvider(Type t, Property p) {
            return p.provider != null && !p.provider.isBlank() ? p.provider
                    : t.defaultProvider != null && !t.defaultProvider.isBlank() ? t.defaultProvider : defaultId;
        }
    }
    public static class GuardedBeanFactory extends org.springframework.beans.factory.support.DefaultListableBeanFactory {
        public boolean metadataOnly;
        @Override public Object getBean(String name) {
            if (metadataOnly) throw new AssertionError("phase A called getBean");
            return super.getBean(name);
        }
    }
    public record Model(SolrNativeFixture fixture) {
        public String getName() { return "synthetic"; }
        public List<ModelType> getSolrIndexedTypes() { return fixture.config.index.types.values().stream().map(ModelType::new).toList(); }
    }
    public record ModelType(Type type) {
        public String getIdentifier() { return type.id; }
        public String getDefaultFieldValueProvider() { return type.defaultProvider; }
        public String getValuesProvider() { return type.fieldsProvider; }
        public List<Property> getSolrIndexedProperties() { return List.copyOf(type.properties.values()); }
    }
    public record Result(List<Model> result) { public List<Model> getResult() { return result; } }
    public record Search(SolrNativeFixture fixture) {
        public Result search(String query) { return new Result(List.of(new Model(fixture))); }
    }
    public record ConfigService(SolrNativeFixture fixture) {
        private void allowed() { if (fixture.failConfigAccess) throw new AssertionError("phase A touched config/cache/converter"); }
        public Config getConfiguration(String name) {
            allowed(); fixture.configReads++; fixture.configurationThread = Thread.currentThread();
            fixture.beforeConfigRead.run();
            if (fixture.loadLegacyInConfig && fixture.type.fieldsProvider != null) fixture.context.getBean(fixture.type.fieldsProvider);
            return fixture.copyConfigurations ? new Config(fixture.cachedConfig) : fixture.cachedConfig;
        }
        public ConfigService getFacetSearchConfigCacheService() { allowed(); return this; }
        public Config putOrGetFromCache(String name) { allowed(); fixture.cacheReads++; return fixture.cachedConfig; }
    }
    public static class Session {
        public final String language;
        public boolean closed;
        public Session(String language) { this.language = language; }
        public void activate() { SESSION.set(this); }
    }
    public static class Sessions {
        public volatile int created, closed;
        public boolean hasCurrentSession() { return SESSION.get() != null; }
        public Session getCurrentSession() { return Objects.requireNonNull(SESSION.get()); }
        public Object getRawSession(Session session) { return session; }
        public Session createNewSession() { created++; var s = new Session("default"); s.activate(); return s; }
        public void closeSession(Session session) { closed++; session.closed = true; if (SESSION.get() == session) SESSION.remove(); }
    }
    public final class IndexService {
        public Index getActiveIndex(String config, String type) {
            if (active == null) throw new IllegalStateException("no active synthetic index");
            return indexes.get(active);
        }
    }
    public record Index(String name, String qualifier, Type type) {
        public String getName() { return name; }
        public String getQualifier() { return qualifier; }
    }
    public record ProviderFactory(SolrNativeFixture fixture) {
        public Object getSearchProvider(Config c, Type t) { return fixture.actualProvider; }
    }
    public static class SearchProvider {
        public SolrNativeFixture fixture;
        public Index resolveIndex(Config c, Type t, String qualifier) { return fixture.indexes.get(qualifier); }
        public Client getClientForIndexing(Index index) {
            fixture.clients++; fixture.constructedAtClient = Provider.constructed.get(); return fixture.client;
        }
        public Client getClient(Index index) { fixture.searched++; throw new AssertionError("must not read a replica"); }
    }
    public record StrategyFactory(SolrNativeFixture fixture) {
        public Strategy createIndexerStrategy(Config config) {
            fixture.strategies++;
            Strategy strategy = fixture.customStrategy ? new Strategy() :
                    new de.hybris.platform.solrfacetsearch.indexer.strategies.impl.DefaultIndexerStrategy();
            strategy.fixture = fixture; return strategy;
        }
    }
    public record Execution(String index, String operation, List<Property> properties, List<?> pks) { }
    public static class Strategy {
        public SolrNativeFixture fixture;
        public Index index;
        public Config config;
        public Type type;
        public List<Property> properties = List.of();
        public List<?> pks;
        public Object operation;
        public void setIndexOperation(Object o) { operation = o; }
        public void setFacetSearchConfig(Config c) { config = c; }
        public void setIndexedType(Type t) { type = t; }
        public void setIndexedProperties(Collection<Property> p) { properties = List.copyOf(p); }
        public void setPks(List<?> p) { pks = p; }
        public void setIndexerHints(Map<?, ?> hints) { }
        public void setIndex(Index i) { index = i; }
        public void execute() throws Exception {
            fixture.beforeExecute.run();
            // The native resolveIndex branch: only look up active when the index was not bound.
            Index target = index == null ? fixture.indexes.get(fixture.active) : index;
            if (fixture.throwExecution) throw new Exception("com.example.secret.Provider at /Users/example/x 'private-value'");
            fixture.executions.add(new Execution(target.name, operation.toString(), properties, pks));
            for (Object pk : Objects.requireNonNull(pks)) {
                if (!fixture.ignoreFailure) {
                    var provider = (Provider) fixture.context.getBean("provider");
                    fixture.documents.put(pk.toString(), provider.value());
                }
                fixture.stores.add(target.name);
            }
        }
    }
    public static class Query {
        public String cursor = "*", sort;
        public Query(String query) { }
        public void setRows(Integer rows) { }
        public void setFields(String... fields) { }
        public void setSort(String name, Object direction) { sort = name; }
        public void set(String key, String... values) { if (key.equals("cursorMark")) cursor = values[0]; }
    }
    public static class Client {
        public SolrNativeFixture fixture;
        public boolean partial, fail, invalidPk;
        public long total = -1;
        public Response query(String core, Query query) {
            fixture.beforeQuery.run();
            if (fail) throw new IllegalStateException("synthetic core unavailable");
            if (!query.sort.equals(fixture.key)) throw new IllegalStateException("uniqueKey required");
            List<Document> docs = new ArrayList<>();
            if (query.cursor.equals("*")) {
                for (String pk : fixture.documents.keySet()) docs.add(new Document(invalidPk ? "invalid" : pk));
            }
            return new Response(new Documents(docs, total < 0 ? fixture.documents.size() : total),
                    partial, query.cursor.equals("*") && !docs.isEmpty() ? "next" : query.cursor);
        }
    }
    public record Document(String pk) { public String getFieldValue(String field) { return pk; } }
    public static class Documents extends ArrayList<Document> {
        public final long total;
        public Documents(Collection<Document> docs, long count) { super(docs); total = count; }
        public long getNumFound() { return total; }
    }
    public record Header(boolean partial) { public Object get(String key) { return key.equals("partialResults") ? partial : null; } }
    public record Response(Documents docs, boolean partial, String cursor) {
        public int getStatus() { return 0; }
        public Header getResponseHeader() { return new Header(partial); }
        public Documents getResults() { return docs; }
        public String getNextCursorMark() { return cursor; }
    }
    public record Schema(String key) { public String getUniqueKey() { return key; } }
}
