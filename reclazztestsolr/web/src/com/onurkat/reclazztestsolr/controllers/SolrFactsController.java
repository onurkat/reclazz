package com.onurkat.reclazztestsolr.controllers;

import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.type.TypeService;
import de.hybris.platform.solrfacetsearch.config.*;
import de.hybris.platform.solrfacetsearch.enums.*;
import de.hybris.platform.solrfacetsearch.indexer.IndexerService;
import de.hybris.platform.solrfacetsearch.model.config.*;
import de.hybris.platform.solrfacetsearch.provider.FieldNameProvider;
import de.hybris.platform.solrfacetsearch.solr.*;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.bind.annotation.*;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

/** Profile-only native oracle; stopped full config/data snapshots own cleanup. */
@RestController
@RequestMapping("/test/batch/solr")
public class SolrFactsController {
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService flexibleSearchService;
    @Autowired private TypeService typeService;
    @Autowired private FacetSearchConfigService facetSearchConfigService;
    @Autowired private SolrIndexService solrIndexService;
    @Autowired private SolrSearchProviderFactory solrSearchProviderFactory;
    @Autowired private IndexerService indexerService;
    @Autowired @Qualifier("solrFieldNameProvider") private FieldNameProvider fieldNameProvider;

    private String owned(String nonce) {
        if (!nonce.matches("reclazz-solr-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid owned cycle nonce");
        return nonce;
    }
    private <T> List<T> rows(String type) {
        if (!Set.of("SolrFacetSearchConfig", "SolrIndexedType", "SolrIndex", "ReclazzSchemaProbe").contains(type))
            throw new IllegalArgumentException("Not a synthetic baseline query");
        return flexibleSearchService.<T>search("SELECT {pk} FROM {" + type + "}").getResult();
    }
    private Object platform() throws Exception {
        Class<?> agent = Class.forName("com.onurkat.reclazz.agent.ReclazzAgent", false, ClassLoader.getSystemClassLoader());
        var field = agent.getDeclaredField("platformContext"); field.setAccessible(true); return field.get(null);
    }
    @GetMapping("/gates")
    public Map<String, Object> gates() throws Exception {
        var result = new LinkedHashMap<String, Object>();
        Class<?> agent = Class.forName("com.onurkat.reclazz.agent.ReclazzAgent", false, ClassLoader.getSystemClassLoader());
        Object config = agent.getMethod("getConfig").invoke(null);
        Object launched = null;
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-javaagent:") && arg.contains("reclazz-agent.jar")) {
                int at = arg.indexOf('=');
                launched = config.getClass().getMethod("parse", String.class).invoke(null, at < 0 ? "" : arg.substring(at + 1));
                result.put("agentArgsFound", true);
            }
        }
        result.putIfAbsent("agentArgsFound", false);
        boolean same = launched != null;
        for (String option : List.of("autoCompile", "autoSolrReindex", "resetStatics", "autoUpdateRunningSystem", "impexAllowRemove")) {
            String getter = "is" + Character.toUpperCase(option.charAt(0)) + option.substring(1);
            boolean actual = launched != null && (Boolean) config.getClass().getMethod(getter).invoke(launched);
            same &= actual == (Boolean) config.getClass().getMethod(getter).invoke(config);
            result.put(option, actual);
        }
        result.put("agentOptionsMatchConfig", same);
        Object p = platform(); Object hybris = p.getClass().getMethod("getHybrisContext").invoke(p);
        var extensions = (Map<?, ?>) hybris.getClass().getMethod("getExtensions").invoke(hybris);
        result.put("solrserverLoaded", extensions.containsKey("solrserver"));
        result.put("solrFixtureLoaded", extensions.containsKey("reclazztestsolr"));
        boolean reachable;
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", 8983), 1000); reachable = true; }
        catch (java.io.IOException absent) { reachable = false; }
        result.put("solrReachable", reachable);
        return result;
    }
    @GetMapping("/baseline")
    public Map<String, Object> baseline() throws Exception {
        var counts = new LinkedHashMap<String, Integer>();
        for (String type : List.of("SolrFacetSearchConfig", "SolrIndexedType", "SolrIndex", "ReclazzSchemaProbe")) counts.put(type, rows(type).size());
        var tenants = new ArrayList<de.hybris.platform.core.Tenant>();
        tenants.add(Registry.getMasterTenant()); tenants.addAll(Registry.getSlaveTenants().values());
        return Map.of("gates", gates(), "counts", counts, "tenants", tenants.stream().map(t ->
                Map.of("id", t.getTenantID(), "db", t.getConfig().getParameter("db.url"))).toList());
    }
    private SolrIndexedPropertyModel property(SolrIndexedTypeModel type, String name, String provider) {
        SolrIndexedPropertyModel property = modelService.create(SolrIndexedPropertyModel.class);
        property.setName(name); property.setType(SolrPropertiesTypes.STRING); property.setSolrIndexedType(type);
        property.setFieldValueProvider(provider); property.setLocalized(false); property.setCurrency(false); property.setMultiValue(false);
        modelService.save(property); return property;
    }
    private SolrIndexedTypeModel indexedType(String nonce, String suffix) {
        SolrIndexedTypeModel type = modelService.create(SolrIndexedTypeModel.class);
        type.setIdentifier(nonce + "-" + suffix); type.setIndexName(nonce + "-" + suffix);
        type.setType(typeService.getComposedTypeForCode("ReclazzSchemaProbe"));
        type.setIdentityProvider("itemIdentityProvider"); type.setModelLoader("defaultModelLoader"); type.setConfigSet("default");
        modelService.save(type);
        SolrIndexerQueryModel query = modelService.create(SolrIndexerQueryModel.class);
        query.setIdentifier(nonce + "-" + suffix + "-query"); query.setType(IndexerOperationValues.FULL);
        query.setQuery("SELECT {pk} FROM {ReclazzSchemaProbe} WHERE {nonce}='" + nonce + "'");
        query.setSolrIndexedType(type); modelService.save(query); return type;
    }
    @PostMapping("/setup")
    public synchronized Map<String, Object> setup(@RequestParam("nonce") String nonce) throws Exception {
        owned(nonce);
        var g = gates();
        for (String gate : List.of("agentArgsFound", "agentOptionsMatchConfig", "autoCompile", "autoSolrReindex", "solrserverLoaded", "solrFixtureLoaded", "solrReachable"))
            if (!Boolean.TRUE.equals(g.get(gate))) throw new IllegalStateException("Setup gate unavailable: " + gate);
        for (String unsafe : List.of("resetStatics", "autoUpdateRunningSystem", "impexAllowRemove"))
            if (!Boolean.FALSE.equals(g.get(unsafe))) throw new IllegalStateException("Unsafe setup option: " + unsafe);
        if (!"master".equals(Registry.getCurrentTenant().getTenantID())) throw new IllegalStateException("Master profile only");
        for (String type : List.of("SolrFacetSearchConfig", "SolrIndexedType", "SolrIndex", "ReclazzSchemaProbe"))
            if (!rows(type).isEmpty()) throw new IllegalStateException("Not an empty synthetic profile baseline: " + type);
        // Native defaults are read in memory, including auth; never serialize credentials.
        var servers = flexibleSearchService.<SolrServerConfigModel>search("SELECT {pk} FROM {SolrServerConfig} WHERE {name}=?name", Map.of("name", "Local Standalone")).getResult();
        var searches = flexibleSearchService.<SolrSearchConfigModel>search("SELECT {pk} FROM {SolrSearchConfig} WHERE {description}=?name", Map.of("name", "Default")).getResult();
        if (servers.size() != 1 || searches.size() != 1 || !"standalone".equals(servers.get(0).getMode().getCode()))
            throw new IllegalStateException("Native standalone defaults unavailable");
        if (servers.get(0).getSolrEndpointUrls().size() != 1 || !"https://localhost:8983/solr".equals(servers.get(0).getSolrEndpointUrls().get(0).getUrl()))
            throw new IllegalStateException("Foreign standalone endpoint");
        ItemModel probe = modelService.create("ReclazzSchemaProbe"); modelService.setAttributeValue(probe, "nonce", nonce); modelService.save(probe);
        SolrIndexConfigModel index = modelService.create(SolrIndexConfigModel.class);
        index.setName(nonce); index.setIndexMode(IndexMode.DIRECT); index.setIgnoreErrors(false); index.setLegacyMode(false);
        index.setDistributedIndexing(false); index.setNumberOfThreads(1); index.setCommitMode(SolrCommitMode.AFTER_INDEX); modelService.save(index);
        SolrIndexedTypeModel target = indexedType(nonce, "target"), control = indexedType(nonce, "control");
        property(target, "changedValue", "reclazzSolrChangedProvider"); property(target, "stableValue", "reclazzSolrStableProvider");
        property(control, "controlValue", "reclazzSolrControlProvider");
        SolrFacetSearchConfigModel config = modelService.create(SolrFacetSearchConfigModel.class);
        config.setName(nonce); config.setIndexNamePrefix(nonce); config.setSolrIndexedTypes(List.of(target, control));
        config.setSolrSearchConfig(searches.get(0)); config.setSolrServerConfig(servers.get(0)); config.setSolrIndexConfig(index);
        config.setEnabledLanguageFallbackMechanism(false); modelService.save(config);
        indexerService.performFullIndex(facetSearchConfigService.getConfiguration(nonce));
        return facts(nonce);
    }
    private String tag(SolrFacetSearchConfigModel config) throws Exception {
        Object p = platform();
        var contexts = (List<?>) p.getClass().getMethod("getAllApplicationContexts").invoke(p);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>()); int owner = 0, mine = 0;
        for (Object object : contexts) for (var ctx = (ConfigurableApplicationContext) object; ctx != null; ctx = (ConfigurableApplicationContext) ctx.getParent()) {
            var factory = (DefaultListableBeanFactory) ctx.getBeanFactory();
            String name = factory.canonicalName("facetSearchConfigService");
            if (!factory.containsSingleton(name)) continue;
            Object service = factory.getSingleton(name);
            if (!seen.add(service)) continue;
            owner++; if (service == facetSearchConfigService) mine = owner;
        }
        var configs = this.<SolrFacetSearchConfigModel>rows("SolrFacetSearchConfig");
        if (mine == 0 || configs.size() != 1 || !configs.get(0).getPk().equals(config.getPk())) throw new IllegalStateException("Ambiguous Solr owner/config");
        int type = 0, found = 0;
        for (SolrIndexedTypeModel t : config.getSolrIndexedTypes()) { type++; if (t.getIdentifier().equals(config.getName() + "-target")) found = type; }
        if (found == 0) throw new IllegalStateException("Target type not in native discovery");
        return "owner" + mine + "/config1/type" + found;
    }
    private Map<String, Object> stored(FacetSearchConfig config, IndexedType type, String nonce, String pk) throws Exception {
        var active = solrIndexService.getActiveIndex(config.getName(), type.getIdentifier());
        var provider = solrSearchProviderFactory.getSearchProvider(config, type);
        if (!provider.getClass().getName().equals("de.hybris.platform.solrfacetsearch.solr.impl.SolrStandaloneSearchProvider"))
            throw new IllegalStateException("Custom search provider");
        Index index = provider.resolveIndex(config, type, active.getQualifier());
        if (!index.getName().contains(nonce)) throw new IllegalStateException("Foreign physical index");
        var client = provider.getClientForIndexing(index);
        try {
            String key = new SchemaRequest.UniqueKey().process(client, index.getName()).getUniqueKey();
            var response = client.query(index.getName(), new SolrQuery("*:*").setRows(10));
            var docs = response.getResults();
            if (response.getStatus() != 0 || docs.getNumFound() != 1 || docs.size() != 1 || !pk.equals(String.valueOf(docs.get(0).getFieldValue("pk"))))
                throw new IllegalStateException("Owned stored document/PK missing");
            var doc = docs.get(0); if (doc.getFieldValue(key) == null) throw new IllegalStateException("Unique key missing");
            var values = new TreeMap<String, Object>(); var fields = new TreeMap<String, String>();
            for (var property : type.getIndexedProperties().values()) {
                var names = fieldNameProvider.getFieldNames(property, null);
                if (names.size() != 1) throw new IllegalStateException("Ambiguous physical property field");
                String field = names.iterator().next(); fields.put(property.getName(), field);
                values.put(property.getName(), doc.getFieldValue(field));
            }
            var result = new LinkedHashMap<String, Object>(); result.put("core", index.getName()); result.put("qualifier", active.getQualifier());
            result.put("uniqueKey", key); result.put("documentId", doc.getFieldValue(key)); result.put("pk", pk);
            var document = new TreeMap<String, Object>();
            for (String name : doc.getFieldNames()) document.put(name, doc.getFieldValue(name));
            result.put("values", values); result.put("fields", fields); result.put("document", document);
            return result;
        } finally { client.close(); }
    }
    private IndexedType ownedIndexedType(FacetSearchConfig config, String identifier) {
        var matches = config.getIndexConfig().getIndexedTypes().values().stream()
                .filter(type -> identifier.equals(type.getIdentifier())).toList();
        if (matches.size() != 1) throw new IllegalStateException("Owned indexed type missing/ambiguous");
        return matches.get(0);
    }
    @GetMapping("/facts")
    public Map<String, Object> facts(@RequestParam("nonce") String nonce) throws Exception {
        owned(nonce);
        var configs = this.<SolrFacetSearchConfigModel>rows("SolrFacetSearchConfig");
        var probes = this.<ItemModel>rows("ReclazzSchemaProbe");
        if (configs.size() != 1 || !nonce.equals(configs.get(0).getName()) || probes.size() != 1 ||
                !nonce.equals(modelService.getAttributeValue(probes.get(0), "nonce"))) throw new IllegalStateException("Owned profile metadata mismatch");
        var config = facetSearchConfigService.getConfiguration(nonce);
        if (config.getIndexConfig().isIgnoreErrors() || config.getIndexConfig().isDistributedIndexing()) throw new IllegalStateException("Unsafe native index flags");
        var target = ownedIndexedType(config, nonce + "-target");
        var control = ownedIndexedType(config, nonce + "-control");
        String pk = probes.get(0).getPk().toString();
        var a = stored(config, target, nonce, pk); var b = stored(config, control, nonce, pk);
        if (a.get("core").equals(b.get("core"))) throw new IllegalStateException("Shared physical core");
        return Map.of("nonce", nonce, "pk", pk, "tag", tag(configs.get(0)), "target", a, "control", b, "gates", gates());
    }
}
