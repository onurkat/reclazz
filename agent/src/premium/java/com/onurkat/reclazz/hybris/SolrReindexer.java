/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Native, local-only indexing. No application or SDK dependency on the agent classpath. */
public final class SolrReindexer implements AutoCloseable {
    private static final String SAP = "de.hybris.platform.solrfacetsearch.";
    private static final int LIMIT = 10_000;
    private final Supplier<List<Object>> contexts;
    private final ScheduledExecutorService worker;
    private final Set<Class<?>> pending = new LinkedHashSet<>();
    private ScheduledFuture<?> scheduled;

    public SolrReindexer(Supplier<List<Object>> contexts) {
        this.contexts = contexts;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Reclazz-Solr");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized boolean enqueue(Class<?> type) {
        if (type == null || worker.isShutdown()) return false;
        pending.add(type);
        if (scheduled != null) scheduled.cancel(false);
        scheduled = worker.schedule(this::drain, 1500, TimeUnit.MILLISECONDS);
        return true;
    }

    private void drain() {
        Set<Class<?>> changed;
        synchronized (this) { changed = Set.copyOf(pending); pending.clear(); }
        run(changed);
    }

    /** Also used by focused native-contract tests, without timing the debounce. */
    void run(Set<Class<?>> changed) {
        try {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            int ownerNumber = 0;
            for (Object context : contexts.get()) {
                for (Object c = context; c != null; c = call(c, "getParent")) {
                    Object bf = call(c, "getBeanFactory");
                    String name = (String) call(bf, "canonicalName", "facetSearchConfigService");
                    if (!yes(call(bf, "containsSingleton", name))) continue;
                    Object service = call(bf, "getSingleton", name);
                    if (!seen.add(service)) continue;
                    Object owner = c;
                    int tag = ++ownerNumber;
                    try { SapOwnerScope.execute(owner, () -> { process(owner, service, changed, tag); return null; }); }
                    catch (Throwable unavailable) { refused("owner" + tag, unavailable); }
                }
            }
            if (seen.isEmpty()) refused("discovery", new Refusal("owning-context-unavailable"));
        } catch (Throwable unavailable) { refused("discovery", unavailable); }
    }

    private record Target(Object context, Object service, Object config, Object type,
                          List<Object> properties, boolean whole, String tag,
                          Set<Class<?>> changed, String mapping, Object cachedConfig, Object cachedType) { }

    private static Object cachedConfig(Object service, Object name) throws Exception {
        return call(call(service, "getFacetSearchConfigCacheService"), "putOrGetFromCache", name);
    }

    private static Object cachedType(Object config, Object identifier) throws Exception {
        Object found = null;
        // Native maps can be keyed by model code rather than the Solr type identifier.
        for (Object candidate : map(call(call(config, "getIndexConfig"), "getIndexedTypes")).values()) {
            if (!Objects.equals(call(candidate, "getIdentifier"), identifier)) continue;
            require(found == null, "ambiguous-type-identifier");
            found = candidate;
        }
        require(found != null, "type-replaced");
        return found;
    }

    record ProviderMapping(String typeProvider, Map<String, String> properties) { }
    record ModelType(String config, String identifier, ProviderMapping mapping,
                     Set<String> selectedProperties, boolean whole, String tag) { }
    record Discovery(List<String> configs, List<ModelType> types) { }

    /** Phase A: persisted strings and Spring type metadata only; never load a native configuration. */
    static Discovery discover(Object ctx, Set<Class<?>> changed, int owner) throws Exception {
        require(yes(call(ctx, "isActive")), "context-closed");
        Object selector = singleton(ctx, "valueProviderSelectionStrategy");
        exact(selector, SAP + "provider.impl.DefaultValueProviderSelectionStrategy", "custom-selector");
        Object beanContext = call(selector, "getApplicationContext");
        String fallback = text(call(selector, "getDefaultValueProviderId"));
        Object result = call(singleton(ctx, "flexibleSearchService"), "search",
                "SELECT {pk} FROM {SolrFacetSearchConfig}");
        List<String> configs = new ArrayList<>();
        List<ModelType> types = new ArrayList<>();
        for (Object config : collection(call(result, "getResult"))) {
            String name = text(call(config, "getName"));
            require(name != null && !configs.contains(name), "ambiguous-config-name");
            configs.add(name);
            Set<String> identifiers = new HashSet<>();
            int typeNumber = 0;
            for (Object type : collection(call(config, "getSolrIndexedTypes"))) {
                String identifier = text(call(type, "getIdentifier"));
                require(identifier != null && identifiers.add(identifier), "ambiguous-type-identifier");
                Map<String, String> providers = new TreeMap<>();
                Set<String> selected = new TreeSet<>();
                String typeProvider = optional(text(call(type, "getValuesProvider")));
                boolean whole = matches(beanContext, typeProvider, changed);
                String typeDefault = text(call(type, "getDefaultFieldValueProvider"));
                for (Object property : collection(call(type, "getSolrIndexedProperties"))) {
                    String propertyName = text(call(property, "getName"));
                    require(propertyName != null && !providers.containsKey(propertyName), "ambiguous-property-name");
                    String id = text(call(property, "getFieldValueProvider"));
                    if (optional(id) == null) id = optional(typeDefault) == null ? fallback : typeDefault;
                    providers.put(propertyName, id);
                    if (matches(beanContext, id, changed)) selected.add(propertyName);
                }
                ProviderMapping mapping = new ProviderMapping(typeProvider, Collections.unmodifiableMap(providers));
                types.add(new ModelType(name, identifier, mapping, Set.copyOf(selected), whole,
                        "owner" + owner + "/config" + configs.size() + "/type" + (++typeNumber)));
            }
        }
        return new Discovery(List.copyOf(configs), List.copyOf(types));
    }

    private static ProviderMapping dtoMapping(Object selector, Object type) throws Exception {
        Map<String, String> providers = new TreeMap<>();
        for (Object property : map(call(type, "getIndexedProperties")).values()) {
            String name = text(call(property, "getName"));
            require(name != null && !providers.containsKey(name), "ambiguous-property-name");
            providers.put(name, text(call(selector, "resolveValueProvider", type, property)));
        }
        return new ProviderMapping(optional(text(call(type, "getFieldsValuesProvider"))),
                Collections.unmodifiableMap(providers));
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value; }

    private static void process(Object ctx, Object service, Set<Class<?>> changed, int owner) throws Exception {
        Discovery discovery = discover(ctx, changed, owner);
        List<ModelType> selected = discovery.types().stream().filter(t -> t.whole() || !t.selectedProperties().isEmpty()).toList();
        if (selected.isEmpty()) { refused("owner" + owner, new Refusal("provider-unused")); return; }

        // Phase B: native configuration construction is now explicit operation preparation.
        // Other configurations are read here solely to retain the v3 physical-ownership guard.
        Map<String, Object> configs = new LinkedHashMap<>(), cached = new LinkedHashMap<>();
        List<Object[]> all = new ArrayList<>();
        for (String name : discovery.configs()) {
            Object source = cachedConfig(service, name);
            Object config = call(service, "getConfiguration", name);
            require(cachedConfig(service, name) == source, "config-replaced");
            configs.put(name, config); cached.put(name, source);
            for (Object type : map(call(call(config, "getIndexConfig"), "getIndexedTypes")).values()) all.add(new Object[]{config, type});
        }
        Object selector = singleton(ctx, "valueProviderSelectionStrategy");
        exact(selector, SAP + "provider.impl.DefaultValueProviderSelectionStrategy", "custom-selector");
        List<Target> targets = new ArrayList<>();
        for (ModelType model : selected) {
            Object config = configs.get(model.config());
            Object type = cachedType(config, model.identifier());
            require(model.mapping().equals(dtoMapping(selector, type)), "configuration-changed-since-discovery");
            List<Object> properties = new ArrayList<>();
            for (Object property : map(call(type, "getIndexedProperties")).values()) {
                if (model.selectedProperties().contains(text(call(property, "getName")))) properties.add(property);
            }
            require(properties.size() == model.selectedProperties().size(), "configuration-changed-since-discovery");
            targets.add(new Target(ctx, service, config, type, List.copyOf(properties), model.whole(),
                    model.tag(), changed, mapping(ctx, selector, type, changed), cached.get(model.config()),
                    cachedType(cached.get(model.config()), model.identifier())));
        }
        // All phase-A/DTO comparisons finish before creating a strategy or an indexing client.
        for (Target target : targets) {
            try { execute(target, all); }
            catch (Throwable failed) { refused(target.tag(), failed); }
        }
    }

    private static boolean matches(Object ctx, String id, Set<Class<?>> changed) throws Exception {
        if (id == null || id.isBlank()) return false;
        for (Object c = ctx; c != null; c = call(c, "getParent")) {
            Object bf = call(c, "getBeanFactory");
            String name = (String) call(bf, "canonicalName", id);
            boolean definition = yes(call(bf, "containsBeanDefinition", name));
            if (!definition && !yes(call(bf, "containsSingleton", name))) continue;
            if (definition) {
                Object bd = call(bf, "getMergedBeanDefinition", name);
                String scope = text(call(bd, "getScope"));
                require(scope == null || Set.of("", "singleton", "prototype").contains(scope), "custom-scoped-provider");
                require(call(bd, "getFactoryMethodName") == null, "factory-method-provider");
                String beanClass = text(call(bd, "getBeanClassName"));
                if (beanClass != null) {
                    Class<?> declared = Class.forName(beanClass, false, loader(c));
                    require(!contract(declared, "org.springframework.beans.factory.FactoryBean")
                            && !beanClass.contains("$$"), "factory-or-proxy-provider");
                }
            }
            Class<?> type = (Class<?>) call(bf, "getType", name, false);
            require(type != null && !type.getName().contains("$$") && !Proxy.isProxyClass(type), "untyped-or-proxy-provider");
            return changed.stream().anyMatch(changedType -> changedType.isAssignableFrom(type));
        }
        throw new Refusal("unresolved-provider");
    }

    private static String mapping(Object ctx, Object selector, Object type, Set<Class<?>> changed) throws Exception {
        ProviderMapping mapping = dtoMapping(selector, type);
        StringBuilder result = new StringBuilder();
        result.append(mapping.typeProvider()).append(':').append(matches(ctx, mapping.typeProvider(), changed));
        for (var entry : mapping.properties().entrySet()) {
            result.append('|').append(entry.getKey()).append(':').append(entry.getValue()).append(':')
                    .append(matches(call(selector, "getApplicationContext"), entry.getValue(), changed));
        }
        return result.toString();
    }

    private static void local(Object config) throws Exception {
        Object solr = call(config, "getSolrConfig");
        require("STANDALONE".equals(String.valueOf(call(solr, "getMode"))), "unsupported-server-mode");
        require(!yes(call(call(config, "getIndexConfig"), "isDistributedIndexing")), "distributed-indexing");
        Collection<?> endpoints = collection(call(solr, "getEndpointURLs"));
        require(!endpoints.isEmpty(), "missing-endpoints");
        for (Object endpoint : endpoints) {
            URI uri = URI.create(text(call(endpoint, "getUrl")));
            require(Set.of("http", "https").contains(uri.getScheme())
                    && uri.getHost() != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                    .contains(uri.getHost().toLowerCase(Locale.ROOT)) && uri.getUserInfo() == null,
                    "non-loopback-endpoint");
        }
    }

    private static Object active(Object ctx, Object config, Object type) throws Exception {
        return call(singleton(ctx, "solrIndexService"), "getActiveIndex",
                call(config, "getName"), call(type, "getIdentifier"));
    }

    private static Object provider(Object ctx, Object config, Object type) throws Exception {
        Object p = call(singleton(ctx, "solrSearchProviderFactory"), "getSearchProvider", config, type);
        exact(p, SAP + "solr.impl.SolrStandaloneSearchProvider", "custom-search-provider");
        return p;
    }

    private static void recheck(Target t, Object index) throws Exception {
        require(yes(call(t.context(), "isActive")), "context-closed");
        local(t.config());
        // The stock service maps a new DTO on every read. The cache object, not that copy,
        // identifies the configuration revision (both supported SDKs).
        Object cached = cachedConfig(t.service(), call(t.config(), "getName"));
        require(cached == t.cachedConfig(), "config-replaced");
        Object cachedType = cachedType(cached, call(t.type(), "getIdentifier"));
        require(cachedType != null && cachedType == t.cachedType(), "type-replaced");
        local(cached);
        Object a = active(t.context(), t.config(), t.type());
        require(Objects.equals(call(a, "getQualifier"), call(index, "getQualifier")), "active-index-switched");
        Object p = provider(t.context(), t.config(), t.type());
        Object resolved = call(p, "resolveIndex", t.config(), t.type(), call(a, "getQualifier"));
        require(Objects.equals(call(resolved, "getName"), call(index, "getName")), "physical-index-changed");
        Object selector = singleton(t.context(), "valueProviderSelectionStrategy");
        exact(selector, SAP + "provider.impl.DefaultValueProviderSelectionStrategy", "custom-selector");
        require(t.mapping().equals(mapping(t.context(), selector, t.type(), t.changed())), "provider-mapping-changed");
        require(t.mapping().equals(mapping(t.context(), selector, cachedType, t.changed())), "provider-mapping-changed");
    }

    private static void execute(Target t, List<Object[]> all) throws Exception {
        local(t.config());
        Object p = provider(t.context(), t.config(), t.type());
        Object a = active(t.context(), t.config(), t.type());
        require(a != null, "missing-active-index");
        Object index = call(p, "resolveIndex", t.config(), t.type(), call(a, "getQualifier"));
        // Config/type names are not an ownership proof: compare native physical names too.
        for (Object[] other : all) {
            if (other[0] == t.config() && other[1] == t.type()) continue;
            try {
                Object oa = active(t.context(), other[0], other[1]);
                Object op = provider(t.context(), other[0], other[1]);
                Object oi = call(op, "resolveIndex", other[0], other[1], call(oa, "getQualifier"));
                require(!Objects.equals(call(index, "getName"), call(oi, "getName")), "shared-physical-index");
            } catch (Exception error) {
                if (!root(error).getClass().getName().equals(SAP + "solr.exceptions.SolrIndexNotFoundException")) throw error;
            }
        }
        Object strategy = call(singleton(t.context(), "indexerStrategyFactory"), "createIndexerStrategy", t.config());
        exact(strategy, SAP + "indexer.strategies.impl.DefaultIndexerStrategy", "custom-indexer-strategy");
        Object client = call(p, "getClientForIndexing", index);
        ClassLoader loader = loader(t.context());
        Object schema = Class.forName("org.apache.solr.client.solrj.request.schema.SchemaRequest$UniqueKey", true, loader)
                .getConstructor().newInstance();
        String key = text(call(call(schema, "process", client, call(index, "getName")), "getUniqueKey"));
        require(key != null && key.matches("[A-Za-z_][A-Za-z0-9_]*"), "unknown-unique-key");
        List<Object> pks = readPks(t, index, client, key, loader);
        if (pks.isEmpty()) { StatusReporter.info("SAP Solr " + t.tag() + ": no stored documents; no indexing call."); return; }
        recheck(t, index);
        Object operation = Class.forName(SAP + "config.IndexOperation", true, loader)
                .getField(t.whole() ? "UPDATE" : "PARTIAL_UPDATE").get(null);
        call(strategy, "setIndexOperation", operation);
        call(strategy, "setFacetSearchConfig", t.config());
        call(strategy, "setIndexedType", t.type());
        if (!t.whole()) call(strategy, "setIndexedProperties", t.properties());
        call(strategy, "setPks", pks);
        call(strategy, "setIndexerHints", Map.of());
        call(strategy, "setIndex", index);
        recheck(t, index);
        String listeners = listenerMetadata(t);
        long started = System.nanoTime();
        call(strategy, "execute");
        boolean ignore = yes(call(call(t.config(), "getIndexConfig"), "isIgnoreErrors"));
        StatusReporter.info("SAP Solr " + t.tag() + ": " + (ignore ? "FINISHED_UNVERIFIED" : "FINISHED")
                + " " + (t.whole() ? "whole-type" : "partial properties=" + t.properties().size())
                + " documents=" + pks.size() + " durationMs=" + (System.nanoTime() - started) / 1_000_000
                + (ignore ? "; native provider errors may have been skipped." : "; finished without native errors.")
                + " nonPlatformListenerMetadata=" + listeners
                + "; normal platform listeners and bookkeeping apply; field freshness is not verified.");
    }

    private static String listenerMetadata(Target t) {
        try {
            Object factory = singleton(t.context(), "listenersFactory");
            exact(factory, SAP + "common.impl.DefaultListenersFactory", "custom-listeners-factory");
            Object raw = call(factory, "getGlobalListeners");
            Set<Object> listeners = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var entry : map(raw).entrySet()) {
                if (!(entry.getKey() instanceof Class<?> contract) || !contract.getName().startsWith(SAP + "indexer.")) continue;
                listeners.addAll(collection(entry.getValue()));
            }
            long count = listeners.stream().filter(l -> !l.getClass().getName().startsWith("de.hybris.platform.")).count();
            Set<String> names = new HashSet<>();
            for (Object object : List.of(call(t.config(), "getIndexConfig"), t.type())) {
                Object declared = call(object, "getListeners");
                if (declared == null) continue;
                for (Object id : collection(declared)) names.add(text(id));
            }
            // Count declared custom listeners from metadata, without invoking getListeners/getBean.
            for (String name : names) {
                Class<?> type = null;
                for (Object c = t.context(); c != null; c = call(c, "getParent")) {
                    Object bf = call(c, "getBeanFactory");
                    String canonical = text(call(bf, "canonicalName", name));
                    if (yes(call(bf, "containsSingleton", canonical)) || yes(call(bf, "containsBeanDefinition", canonical))) {
                        type = (Class<?>) call(bf, "getType", canonical, false); break;
                    }
                }
                if (type == null) return "unknown";
                if (!type.getName().startsWith("de.hybris.platform.")) count++;
            }
            return Long.toString(count);
        } catch (Exception | LinkageError unavailable) { return "unknown"; }
    }

    private static List<Object> readPks(Target t, Object index, Object client, String key, ClassLoader loader) throws Exception {
        Map<String, Object> pks = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = "*";
        long total = -1, read = 0;
        Class<?> queryType = Class.forName("org.apache.solr.client.solrj.SolrQuery", true, loader);
        Object asc = Class.forName("org.apache.solr.client.solrj.SolrQuery$ORDER", true, loader).getField("asc").get(null);
        Class<?> pkType = Class.forName("de.hybris.platform.core.PK", true, loader);
        for (int page = 0; page < 12; page++) {
            recheck(t, index);
            require(cursors.add(cursor), "cursor-cycle");
            Object query = queryType.getConstructor(String.class).newInstance("*:*");
            call(query, "setRows", 1000);
            call(query, "setFields", (Object) new String[]{"pk", key});
            call(query, "setSort", key, asc);
            call(query, "set", "cursorMark", (Object) new String[]{cursor});
            Object response = call(client, "query", call(index, "getName"), query);
            require(((Number) call(response, "getStatus")).intValue() == 0, "query-status");
            Object partial = call(call(response, "getResponseHeader"), "get", "partialResults");
            require(partial == null || Boolean.FALSE.equals(partial) || "false".equals(partial), "partial-query-results");
            Object docs = call(response, "getResults");
            long found = ((Number) call(docs, "getNumFound")).longValue();
            require(found >= 0 && found <= LIMIT && (total == -1 || total == found), "document-limit-or-index-changed");
            total = found;
            for (Object doc : collection(docs)) {
                require(++read <= LIMIT, "document-limit");
                Object value = call(doc, "getFieldValue", "pk");
                String raw = text(value);
                require(raw != null && raw.matches("[1-9][0-9]*"), "invalid-stored-pk");
                Object pk = call(pkType, "parse", raw);
                require(raw.equals(String.valueOf(call(pk, "getLongValue"))), "invalid-stored-pk");
                pks.putIfAbsent(raw, pk);
            }
            String next = text(call(response, "getNextCursorMark"));
            require(next != null, "missing-cursor");
            recheck(t, index);
            if (cursor.equals(next)) {
                require(read == total && collection(docs).isEmpty(), "incomplete-pk-snapshot");
                return List.copyOf(pks.values());
            }
            cursor = next;
        }
        throw new Refusal("pagination-limit");
    }

    private static Object singleton(Object ctx, String id) throws Exception {
        return SapOwnerScope.singleton(ctx, id);
    }

    private static ClassLoader loader(Object ctx) throws Exception {
        return (ClassLoader) call(call(ctx, "getBeanFactory"), "getBeanClassLoader");
    }
    private static boolean contract(Class<?> type, String name) {
        if (type == null) return false;
        if (type.getName().equals(name)) return true;
        for (Class<?> i : type.getInterfaces()) if (contract(i, name)) return true;
        return contract(type.getSuperclass(), name);
    }
    private static void exact(Object value, String name, String reason) throws Refusal {
        require(value != null && value.getClass().getName().equals(name), reason);
    }
    private static void require(boolean condition, String reason) throws Refusal {
        if (!condition) throw new Refusal(reason);
    }
    private static final class Refusal extends Exception {
        Refusal(String reason) { super(reason); }
    }
    private static Throwable root(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error;
    }
    private static void refused(String tag, Throwable error) {
        Throwable cause = root(error);
        // No native messages or class names: both can contain customer identifiers or indexed values.
        String reason = (cause instanceof Refusal || cause instanceof SapOwnerScope.Refusal) ? cause.getMessage() : "native-operation-failed";
        StatusReporter.info("SAP Solr " + tag + ": FAILED (" + reason + "). Reclazz did not confirm "
                + "a reindex of stored documents; validate and explicitly reindex affected documents if needed.");
    }
    private static boolean yes(Object value) { return Boolean.TRUE.equals(value); }
    private static String text(Object value) { return value == null ? null : value.toString(); }
    private static Collection<?> collection(Object value) throws Refusal {
        require(value instanceof Collection<?>, "unsupported-native-collection");
        return (Collection<?>) value;
    }
    private static Map<?, ?> map(Object value) throws Refusal {
        require(value instanceof Map<?, ?>, "unsupported-native-map");
        return (Map<?, ?>) value;
    }

    /** Resolve public SDK/Spring methods, plus the inspected package-private tenant context getter. */
    private static Object call(Object receiver, String name, Object... args) throws Exception {
        require(receiver != null, "missing-native-object");
        Class<?> type = receiver instanceof Class<?> c ? c : receiver.getClass();
        List<Method> methods = new ArrayList<>(Arrays.asList(type.getMethods()));
        if (name.equals("getApplicationContext") || name.equals("getGlobalListeners")) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) methods.addAll(Arrays.asList(c.getDeclaredMethods()));
        }
        for (Method method : methods) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                Class<?> parameter = params[i] == boolean.class ? Boolean.class
                        : params[i] == int.class ? Integer.class : params[i];
                if (args[i] != null && !parameter.isInstance(args[i])) match = false;
            }
            if (!match) continue;
            if (!method.trySetAccessible()) throw new Refusal("native-method-inaccessible");
            try { return method.invoke(receiver instanceof Class<?> ? null : receiver, args); }
            catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw e;
            }
        }
        throw new Refusal("native-method-unavailable");
    }

    @Override public synchronized void close() {
        if (scheduled != null) scheduled.cancel(false);
        pending.clear();
        worker.shutdown();
    }
}
