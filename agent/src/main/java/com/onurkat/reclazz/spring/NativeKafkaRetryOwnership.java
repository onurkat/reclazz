/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import com.onurkat.reclazz.bootstrap.KafkaStartupRetry;
import com.onurkat.reclazz.util.Reflect;
import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.reflect.Method;
import java.util.*;

/** Provenance for the native listener processor's startup registrations. */
public final class NativeKafkaRetryOwnership implements KafkaStartupRetry.Observer {
    public static void install() {
        // Called only after the bootstrap JAR is installed. Keep Observer out of
        // premain verification, which runs before the bootstrap search path changes.
        KafkaStartupRetry.observe(new NativeKafkaRetryOwnership());
    }

    private record Prior(Object resolver, Set<?> existingKeys) { }
    private static final Map<Object,Context> contexts = new WeakHashMap<>();
    private static final class Context {
        final Queue<Pending> pending = new ConcurrentLinkedQueue<>();
        final Map<Class<?>,Map<String,String>> retired = new WeakHashMap<>();
    }
    record Receipt(Object resolver, Method method, String mainId, String version,
                   Map<?,?> routes, Map<String,Object> products) { }

    // Nothing retained by a weak factory key may lead back to its application context.
    // Containers may be constructed after the configurer has discarded its endpoint.
    private record Pending(WeakReference<Object> resolver, WeakReference<Object> registrar,
            WeakReference<Class<?>> owner, String signature, String mainId, String version,
            Map<Object,WeakReference<Object>> routes, Map<Object,Map<Object,WeakReference<Object>>> startupTopics,
            Map<String,WeakReference<Object>> products,
            Map<String,Supplier<Object>> containers) { }

    private static synchronized Context context(Object factory, boolean create) {
        return create ? contexts.computeIfAbsent(factory, ignored -> new Context()) : contexts.get(factory);
    }

    private static String signature(Method method) {
        return method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
    }

    private static <K> Map<K,WeakReference<Object>> weak(Map<K,Object> values) {
        var refs = new LinkedHashMap<K,WeakReference<Object>>();
        values.forEach((key,value) -> refs.put(key, new WeakReference<>(value)));
        return refs;
    }

    private static <K> Map<K,Object> live(Map<K,WeakReference<Object>> refs) {
        var values = new LinkedHashMap<K,Object>();
        for (var entry : refs.entrySet()) {
            Object value = entry.getValue().get();
            if (value == null) throw new IllegalStateException("Kafka startup product no longer live");
            values.put(entry.getKey(), value);
        }
        return values;
    }

    @Override public Object before(Object[] args) {
        Object processor = Reflect.readField(args[0], "destinationTopicProcessor");
        if (processor == null) return null;
        Object resolver = Reflect.readField(processor, "destinationTopicResolver");
        if (resolver == null || !Boolean.FALSE.equals(Reflect.readField(resolver, "contextRefreshed"))
                || !resolver.getClass().getName().equals("org.springframework.kafka.retrytopic.DefaultDestinationTopicResolver"))
            return null;
        var raw = (Map<?,?>) Reflect.readField(resolver, "sourceDestinationsHolderMap");
        return new Prior(resolver, Set.copyOf(raw.keySet()));
    }

    @Override public void completed(Object[] args, Object snapshot, List<Object> endpoints, Map<String,Object> products)
            throws ReflectiveOperationException {
        StatusReporter.detail("Kafka startup retry observed " + endpoints.size() + " endpoints and " + products.size() + " topic beans");
        if (endpoints.isEmpty() || !endpoints.contains(args[2])
                || !args[2].getClass().getName().equals("org.springframework.kafka.config.MethodKafkaListenerEndpoint")) return;
        var prior = (Prior) snapshot;
        Object main = args[2];
        Method callback = (Method) getter(main, "getMethod");
        if (callback == null) return;
        String id = (String) getter(main, "getId");
        String version = args[0].getClass().getPackage().getImplementationVersion();
        var raw = (Map<?,?>) Reflect.readField(prior.resolver(), "sourceDestinationsHolderMap");
        var ids = new LinkedHashMap<String,Supplier<Object>>();
        var topics = new LinkedHashSet<Object>();
        for (Object endpoint : endpoints) {
            var identity = KafkaStartupRetry.identityFor(endpoint);
            if (identity == null || ids.putIfAbsent((String) getter(endpoint, "getId"), identity) != null) return;
            topics.addAll((Collection<?>) getter(endpoint, "getTopics"));
        }
        var entries = new LinkedHashMap<Object,Object>();
        // The 2.x resolver indexes every source topic separately; 3.x indexes the
        // listener ID with a second topic map. Pre-existing entries cannot be claimed.
        Set<?> keys = version.equals("2.9.13") ? topics : Set.of(id);
        for (Object key : keys) {
            Object value = raw.get(key);
            if (prior.existingKeys().contains(key) || value == null) return;
            if (value instanceof Map<?,?> nested && !nested.keySet().equals(topics)) return;
            entries.put(key, value);
        }
        if (entries.isEmpty()) return;
        StatusReporter.detail("Kafka startup retry captured " + id + " with " + entries.size() + " destination entries");
        Object factory = Reflect.readField(args[0], "beanFactory");
        recordStartup(factory, prior.resolver(), args[4], callback, id, version, entries, products, ids);
    }

    static void recordStartup(Object factory, Object resolver, Object registrar, Method callback, String id, String version,
            Map<Object,Object> entries, Map<String,Object> products, Map<String,Supplier<Object>> containers) {
        var startupTopics = new LinkedHashMap<Object,Map<Object,WeakReference<Object>>>();
        entries.forEach((key, value) -> {
            if (value instanceof Map<?,?> topics) {
                var refs = new LinkedHashMap<Object,WeakReference<Object>>();
                topics.forEach((topic, holder) -> refs.put(topic, new WeakReference<>(holder)));
                startupTopics.put(key, refs);
            }
        });
        context(factory, true).pending.add(new Pending(new WeakReference<>(resolver), new WeakReference<>(registrar),
                new WeakReference<>(callback.getDeclaringClass()), signature(callback), id, version,
                weak(entries), startupTopics, weak(products), Map.copyOf(containers)));
    }

    // groups() holds the per-factory ownership lock. The startup observer only enqueues.
    static void materialize(Object factory) {
        Context state = context(factory, false);
        if (state == null) return;
        for (var receipts = state.pending.iterator(); receipts.hasNext();) {
            Pending receipt = receipts.next();
            try {
                Object registrar = receipt.registrar().get();
                Object registry = registrar == null ? null : getter(registrar, "getEndpointRegistry");
                if (registrar != null && (registry == null || !Boolean.TRUE.equals(Reflect.readField(registry, "contextRefreshed"))))
                    continue;
                if (registry != null) adopt(factory, registry, receipt);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                StatusReporter.detail("Kafka startup retry ownership unavailable for " + receipt.mainId()
                        + ": " + com.onurkat.reclazz.ui.Failures.describe(failure));
            }
            receipts.remove();
        }
    }

    private static void adopt(Object factory, Object registry, Pending receipt) throws ReflectiveOperationException {
        Class<?> owner = receipt.owner().get();
        Object resolver = receipt.resolver().get();
        if (owner == null || resolver == null) return;
        var members = new ArrayList<SpringKafkaReloader.Container>();
        for (var entry : receipt.containers().entrySet()) {
            Object expected = entry.getValue().get();
            if (expected == null || expected != SpringKafkaReloader.container(registry, entry.getKey())) return;
            members.add(new SpringKafkaReloader.Container(registry, entry.getKey(), expected));
        }
        var routes = live(receipt.routes());
        for (var entry : receipt.startupTopics().entrySet()) {
            var current = (Map<?,?>) routes.get(entry.getKey());
            var original = live(entry.getValue());
            if (current.size() != original.size()
                    || original.entrySet().stream().anyMatch(topic -> current.get(topic.getKey()) != topic.getValue())) return;
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (!receipt.signature().equals(signature(method))) continue;
            SpringKafkaRetryRegistration.adopt(factory, new Receipt(resolver, method, receipt.mainId(),
                    receipt.version(), routes, live(receipt.products())), members);
            return;
        }
    }

    static void retired(Object factory, Method callback, String mainId) {
        if (callback == null) return;
        Context state = context(factory, true);
        synchronized (state.retired) {
            state.retired.computeIfAbsent(callback.getDeclaringClass(), ignored -> new HashMap<>())
                    .put(signature(callback), mainId);
        }
    }

    @Override public boolean replay(Object[] args) throws ReflectiveOperationException {
        Object factory = Reflect.readField(args[0], "beanFactory");
        if (factory == null) return false;
        Context state;
        Method callback;
        String id;
        try {
            state = context(factory, false);
            if (state == null) return false;
            callback = (Method) getter(args[2], "getMethod");
            if (callback == null) return false;
            synchronized (state.retired) {
                var ids = state.retired.get(callback.getDeclaringClass());
                if (ids == null) return false;
                id = ids.remove(signature(callback));
                if (ids.isEmpty()) state.retired.remove(callback.getDeclaringClass());
                if (id == null) return false;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // An unrecognized application factory still follows its native path.
            return false;
        }
        try {
            var group = SpringKafkaRetryRegistration.stage(args[0], args[1], args[2], args[3], args[4], args[5],
                    (String)args[6], callback.getDeclaringClass());
            group.nativeCallback = callback;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            retired(factory, callback, id);
            throw failure;
        }
    }

    private static Object getter(Object object, String name) throws ReflectiveOperationException {
        return object.getClass().getMethod(name).invoke(object);
    }
}
