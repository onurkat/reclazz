/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Lifecycle coordination for standard Spring Kafka containers around bean recreation. */
public final class SpringKafkaReloader {
    private static final String PROCESSOR = "org.springframework.kafka.annotation.KafkaListenerAnnotationBeanPostProcessor";
    private static final String REGISTRY = "org.springframework.kafka.config.KafkaListenerEndpointRegistry";
    private static final String RECORD_ADAPTER = "org.springframework.kafka.listener.adapter.RecordMessagingMessageListenerAdapter";
    private final PlatformContext platform;
    private static final class OwnerState { volatile boolean registered; }
    private final ClassValue<OwnerState> owners = new ClassValue<>() {
        @Override protected OwnerState computeValue(Class<?> type) { return new OwnerState(); }
    };
    public SpringKafkaReloader(PlatformContext platform) { this.platform = platform; }
    private record Infrastructure(Object context, Object processor, Object registry) { }
    private record Container(Object registry, String id, Object instance) { }

    /** Read-only preflight for refusing mixed JMS/Kafka owners before shutdown. */
    synchronized boolean hasListeners(Class<?> type, byte[] bytes) {
        if (owners.get(type).registered || declaresKafka(type, bytes)) return true;
        try {
            for (var scope : infrastructures()) for (var c : containers(scope.registry())) {
                Object bean = listenerBean(messageListener(c.instance()));
                if (AddedKafkaListenerAdapter.ownerOf(bean) == type || (bean != null && type.isInstance(bean))) return true;
            }
            return false;
        } catch (Throwable unavailable) {
            // Only called for a JMS-relevant bean. Uncertain Kafka ownership
            // must be resolved before the JMS side retires any consumers.
            return true;
        }
    }

    public synchronized boolean beforeBeanRefresh(Class<?> type) {
        return beforeBeanRefresh(type, null);
    }

    public synchronized boolean beforeBeanRefresh(Class<?> type, byte[] bytes) {
        boolean relevant = owners.get(type).registered;
        try {
            relevant |= declaresKafka(type, bytes);
            List<Infrastructure> scopes = infrastructures();
            List<Container> selected = new ArrayList<>();
            for (var scope : scopes) {
                Object cache = Reflect.readField(scope.processor(), "nonAnnotatedClasses");
                if (!(cache instanceof Set<?>)) throw new IllegalStateException("Kafka negative scan cache is inaccessible");
                for (var c : containers(scope.registry())) {
                    Object listener = messageListener(c.instance());
                    Object bean = listenerBean(listener);
                    if (AddedKafkaListenerAdapter.ownerOf(bean) != type && (bean == null || !type.isInstance(bean))) continue;
                    relevant = true;
                    owners.get(type).registered = true;
                    if (!listener.getClass().getName().equals(RECORD_ADAPTER))
                        throw new IllegalStateException("custom or batch Kafka listener adapter for " + c.id());
                    if (AddedKafkaListenerAdapter.ownerOf(bean) != type)
                        throw new IllegalStateException("proxy or subclass Kafka listener receiver for " + c.id());
                    // Validate every selected entry before stopping any of them.
                    requireLifecycle(c);
                    selected.add(c);
                }
            }
            stopAndRemove(selected);
            for (var scope : scopes) ((Set<?>) Reflect.readField(scope.processor(), "nonAnnotatedClasses")).remove(type);
            return true;
        } catch (Throwable failure) {
            // An optional unsupported Kafka stack must not stop an unrelated
            // controller/service from following its ordinary reload path.
            if (!relevant) return true;
            report(type, "bean recreation deferred: " + Failures.describe(failure));
            return false;
        }
    }

    public synchronized boolean reloadKafkaListeners(Class<?> type, Set<String> added, byte[] bytes) {
        var plan = AddedKafkaListenerAdapter.inspect(bytes, added);
        if (!plan.refused().isEmpty()) {
            for (String reason : plan.refused()) report(type, reason);
            return false;
        }
        if (plan.methods().isEmpty()) {
            owners.get(type).registered = false;
            return false;
        }
        boolean changed = false, found = false;
        List<Container> created = new ArrayList<>();
        try {
            for (var scope : infrastructures()) {
                String[] names = SpringBeans.beanNamesForType(scope.context(), type);
                if (names.length == 0) continue;
                found = true;
                if (names.length != 1) throw new IllegalStateException("literal listener IDs require one matching bean per context");
                Object factory = SpringBeans.getBeanFactory(scope.context());
                String name = names[0];
                if (!(Boolean) factory.getClass().getMethod("isSingleton", String.class).invoke(factory, name))
                    throw new IllegalStateException("only singleton Kafka listener beans are supported");
                Supplier<Object> current = currentSingleton(factory, name, type);
                current.get();
                if (type.getSuperclass() != Object.class || type.getInterfaces().length != 0)
                    throw new IllegalStateException("listener inheritance/interfaces are unsupported");
                if (Reflect.readField(scope.processor(), "enhancer") != null
                        || SpringBeans.beanNamesForType(scope.context(), "org.springframework.kafka.retrytopic.RetryTopicConfiguration").length != 0)
                    throw new IllegalStateException("annotation enhancers and retry-topic configurations require a restart");
                for (String id : plan.ids()) if (container(scope.registry(), id) != null)
                    throw new IllegalStateException("listener id already belongs to an existing container: " + id);
                for (var method : plan.methods()) requireFactory(scope, method);
                var delegate = AddedKafkaListenerAdapter.create(type, current, plan);
                Class<?> annotation = Class.forName("org.springframework.kafka.annotation.KafkaListener", false, type.getClassLoader());
                for (int i = 0; i < delegate.methods().size(); i++) {
                    Method method = delegate.methods().get(i);
                    Object metadata = Arrays.stream(method.getDeclaredAnnotations()).filter(a -> a.annotationType() == annotation).findFirst().orElseThrow();
                    try {
                        SpringKafkaListenerExpressions.register(scope.processor(), annotation, metadata, method,
                                delegate.bean(), current.get(), name);
                    } finally {
                        Object registered = container(scope.registry(), plan.ids().get(i));
                        if (registered != null && listenerBean(messageListener(registered)) == delegate.bean()) {
                            created.add(new Container(scope.registry(), plan.ids().get(i), registered));
                            owners.get(type).registered = true;
                        }
                    }
                    Object registered = container(scope.registry(), plan.ids().get(i));
                    if (registered == null) throw new IllegalStateException("Kafka processor did not register " + plan.ids().get(i));
                    if (!messageListener(registered).getClass().getName().equals(RECORD_ADAPTER))
                        throw new IllegalStateException("custom or batch listener adapters require a restart");
                    changed = true;
                }
            }
            if (!found) throw new IllegalStateException("no matching singleton with a Kafka listener processor was found");
        } catch (Throwable failure) {
            try { stopAndRemove(created); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            report(type, Failures.describe(failure));
            return false;
        }
        if (changed) ReloadEffects.note("added @KafkaListener registered");
        return changed;
    }

    private static boolean declaresKafka(Class<?> type, byte[] bytes) {
        String prefix = "org.springframework.kafka.annotation.";
        if (bytes == null) {
            for (var a : type.getDeclaredAnnotations()) if (a.annotationType().getName().startsWith(prefix)) return true;
            for (var m : type.getDeclaredMethods()) for (var a : m.getDeclaredAnnotations())
                if (a.annotationType().getName().startsWith(prefix)) return true;
            return false;
        }
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG);
        String descriptor = "Lorg/springframework/kafka/annotation/";
        if (node.visibleAnnotations != null) for (var a : node.visibleAnnotations) if (a.desc.startsWith(descriptor)) return true;
        for (var m : node.methods) if (m.visibleAnnotations != null)
            for (var a : m.visibleAnnotations) if (a.desc.startsWith(descriptor)) return true;
        return false;
    }

    private static void requireFactory(Infrastructure scope, org.objectweb.asm.tree.MethodNode method) throws Exception {
        Object defaultName = Reflect.readField(scope.processor(), "defaultContainerFactoryBeanName");
        if (!(defaultName instanceof String name)) throw new IllegalStateException("Kafka default container factory is inaccessible");
        for (var a : method.visibleAnnotations) if (a.desc.equals(AddedKafkaListenerAdapter.KAFKA) && a.values != null)
            for (int i = 0; i < a.values.size(); i += 2)
                if (a.values.get(i).equals("containerFactory")) name = (String) a.values.get(i + 1);
        requireFactory(scope, name);
    }

    private static void requireFactory(Infrastructure scope, String name) throws Exception {
        if (name.isEmpty()) {
            Object defaultName = Reflect.readField(scope.processor(), "defaultContainerFactoryBeanName");
            if (!(defaultName instanceof String value)) throw new IllegalStateException("Kafka default factory inaccessible");
            name = value;
        }
        if (name.isBlank() || name.contains("${") || name.contains("#{"))
            throw new IllegalStateException("literal Kafka factory required");
        Object factory = SpringBeans.getBean(scope.context(), name);
        if (factory == null || !factory.getClass().getName().equals("org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory"))
            throw new IllegalStateException("standard ConcurrentKafkaListenerContainerFactory required: " + name);
        for (String field : List.of("recordFilterStrategy", "retryTemplate", "containerCustomizer", "batchToRecordAdapter")) {
            var metadata = Reflect.findField(factory.getClass(), field);
            // Kafka 3.2.4 removed this field; unknown or inaccessible shapes still refuse.
            if (metadata == null && field.equals("retryTemplate")
                    && "3.2.4".equals(factory.getClass().getPackage().getImplementationVersion())) {
                try { factory.getClass().getSuperclass().getDeclaredField(field); }
                catch (NoSuchFieldException absentOnVerifiedVersion) { continue; }
            }
            if (metadata == null || metadata.get(factory) != null)
                throw new IllegalStateException("Kafka factory " + field + " is unsupported for added listeners");
        }
        if (Boolean.TRUE.equals(factory.getClass().getMethod("isBatchListener").invoke(factory)))
            throw new IllegalStateException("batch Kafka factories require a restart for added listeners");
    }

    private List<Infrastructure> infrastructures() throws Exception {
        return infrastructures(platform.getAllApplicationContexts());
    }
    private static List<Infrastructure> infrastructures(List<Object> contexts) throws Exception {
        List<Infrastructure> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object context : contexts) {
            String[] names = SpringBeans.beanNamesForType(context, PROCESSOR);
            if (names.length == 0) continue;
            if (names.length != 1) throw new IllegalStateException("multiple Kafka listener processors are unsupported");
            Object processor = SpringBeans.getBean(context, names[0]);
            if (processor == null || !processor.getClass().getName().equals(PROCESSOR))
                throw new IllegalStateException("custom Kafka listener processors are unsupported");
            Object registrar = processor.getClass().getMethod("getEndpointRegistrar").invoke(processor);
            Object registry = registrar.getClass().getMethod("getEndpointRegistry").invoke(registrar);
            if (registry == null || !registry.getClass().getName().equals(REGISTRY))
                throw new IllegalStateException("standard Kafka endpoint registry is required");
            if (seen.add(registry)) result.add(new Infrastructure(context, processor, registry));
        }
        return result;
    }
    private static Object container(Object registry, String id) throws Exception {
        return registry.getClass().getMethod("getListenerContainer", String.class).invoke(registry, id);
    }
    private static List<Container> containers(Object registry) throws Exception {
        Object ids = registry.getClass().getMethod("getListenerContainerIds").invoke(registry);
        if (!(ids instanceof Set<?> set)) throw new IllegalStateException("Kafka container IDs are inaccessible");
        List<Container> result = new ArrayList<>();
        for (Object id : new ArrayList<>(set)) {
            Object value = container(registry, (String) id);
            if (value != null) result.add(new Container(registry, (String) id, value));
        }
        return result;
    }
    private static Object messageListener(Object container) throws Exception {
        Object properties = container.getClass().getMethod("getContainerProperties").invoke(container);
        return properties.getClass().getMethod("getMessageListener").invoke(properties);
    }
    private static Object listenerBean(Object listener) throws Exception {
        if (listener == null) return null;
        Class<?> delegating = Class.forName("org.springframework.kafka.listener.DelegatingMessageListener", false, listener.getClass().getClassLoader());
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (delegating.isInstance(listener)) {
            if (!visited.add(listener)) throw new IllegalStateException("cyclic Kafka listener wrapper");
            listener = delegating.getMethod("getDelegate").invoke(listener);
        }
        return Reflect.readField(listener, "bean");
    }
    private static void requireLifecycle(Container c) throws Exception {
        c.registry().getClass().getMethod("unregisterListenerContainer", String.class);
        c.instance().getClass().getMethod("stop", Runnable.class);
        c.instance().getClass().getMethod("isRunning");
    }
    private static void stopAndRemove(List<Container> selected) throws Exception {
        CountDownLatch stopped = new CountDownLatch(selected.size());
        for (var c : selected) requireLifecycle(c);
        for (var c : selected) c.instance().getClass().getMethod("stop", Runnable.class).invoke(c.instance(), (Runnable) stopped::countDown);
        try {
            if (!stopped.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Kafka consumers did not finish stopping within 30 seconds; registry entries retained");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        for (var c : selected) {
            if ((Boolean) c.instance().getClass().getMethod("isRunning").invoke(c.instance()))
                throw new IllegalStateException("Kafka container is still running: " + c.id());
            if (container(c.registry(), c.id()) != c.instance())
                throw new IllegalStateException("Kafka container ownership changed during shutdown: " + c.id());
            c.registry().getClass().getMethod("unregisterListenerContainer", String.class).invoke(c.registry(), c.id());
        }
    }
    private static Supplier<Object> currentSingleton(Object factory, String name, Class<?> type) throws Exception {
        Method get = factory.getClass().getMethod("getSingleton", String.class);
        return () -> {
            try {
                Object bean = get.invoke(factory, name);
                if (bean == null || bean.getClass() != type)
                    throw new IllegalStateException("Kafka callback requires current plain singleton " + name + "; missing/proxy/subclass receiver refused");
                return bean;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot read Kafka singleton " + name, failure); }
        };
    }
    private record RegistryScope(Object context, Object registry) { }

    /** Read native callback ownership without assuming a supported processor configuration. */
    private static List<RegistryScope> registryScopes(List<Object> contexts) throws Exception {
        List<RegistryScope> result = new ArrayList<>();
        for (Object context : contexts) {
            Object factory = SpringBeans.getBeanFactory(context);
            if (factory == null) continue;
            Class<?> registryType;
            try { registryType = Class.forName(REGISTRY, false, context.getClass().getClassLoader()); }
            catch (ClassNotFoundException absent) { continue; }
            String[] names = (String[]) factory.getClass().getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class)
                    .invoke(factory, registryType, true, false);
            for (String name : names) {
                Object registry = factory.getClass().getMethod("getBean", String.class).invoke(factory, name);
                if (registry == null) throw new IllegalStateException("Kafka registry ownership is inaccessible");
                result.add(new RegistryScope(context, registry));
            }
        }
        return result;
    }

    private static boolean hasDependentConsumers(List<Object> contexts, String root) throws Exception {
        for (Object context : contexts) {
            Object factory = SpringBeans.getBeanFactory(context);
            if (factory == null) continue;
            String[] dependents = (String[]) factory.getClass().getMethod("getDependentBeans", String.class).invoke(factory, root);
            if (dependents.length == 0) continue;
            for (var scope : registryScopes(List.of(context))) for (var container : containers(scope.registry())) {
                Object receiver = listenerBean(messageListener(container.instance()));
                for (String name : dependents) {
                    Object bean = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, name);
                    if (bean != null && (receiver == bean || AddedKafkaListenerAdapter.ownerOf(receiver) == bean.getClass())) return true;
                }
            }
        }
        return false;
    }

    /** A direct property target is not a consumer lifecycle transaction. */
    static void requireNoPropertyConsumer(List<Object> contexts, Object context, String name) throws Exception {
        Object factory = SpringBeans.getBeanFactory(context);
        if (factory == null) return;
        Object bean = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, name);
        if (bean == null) return;
        boolean owned = declaresKafka(bean.getClass(), null);
        if (!owned) for (var scope : registryScopes(contexts)) for (var container : containers(scope.registry())) {
            Object receiver = listenerBean(messageListener(container.instance()));
            if (receiver == bean || AddedKafkaListenerAdapter.ownerOf(receiver) == bean.getClass()) owned = true;
        }
        if (owned) {
            RestartLedger.note(name, "direct Kafka constructor-property refresh needs a coordinated helper or restart");
            throw new IllegalStateException("direct Kafka property consumer refused before singleton destruction: " + name);
        }
    }

    /**
     * A bounded helper -> leaf Kafka owner refresh. Read the native edges and every
     * callback before Spring's recursive destruction removes that information.
     * Null means no owned Kafka dependent; unsupported affected shapes throw.
     */
    static DependencyRefresh prepareDependencyRefresh(List<Object> contexts, Object context, String root) throws Exception {
        try { return inspectDependencyRefresh(contexts, context, root); }
        catch (Exception failure) {
            RestartLedger.note(root, "Kafka dependency refresh refused before singleton destruction: " + Failures.describe(failure));
            throw failure;
        }
    }

    private static DependencyRefresh inspectDependencyRefresh(List<Object> contexts, Object context, String root) throws Exception {
        Object factory = SpringBeans.getBeanFactory(context);
        if (factory == null) return null;
        if (!hasDependentConsumers(contexts, root)) return null;
        List<Infrastructure> scopes = infrastructures(contexts);
        if (scopes.isEmpty()) throw new IllegalStateException("owned Kafka dependency has no standard processor");
        String[] dependents = (String[]) factory.getClass().getMethod("getDependentBeans", String.class).invoke(factory, root);
        // A child factory records its own dependency on a parent helper. Do not
        // miss that ownership merely because the root factory has no such edge.
        for (var scope : scopes) if (scope.context() != context) {
            Object foreign = SpringBeans.getBeanFactory(scope.context());
            String[] names = (String[]) foreign.getClass().getMethod("getDependentBeans", String.class).invoke(foreign, root);
            for (String name : names) {
                Object bean = foreign.getClass().getMethod("getSingleton", String.class).invoke(foreign, name);
                for (var c : containers(scope.registry())) {
                    Object receiver = listenerBean(messageListener(c.instance()));
                    if (bean != null && (receiver == bean || AddedKafkaListenerAdapter.ownerOf(receiver) == bean.getClass()))
                        throw new IllegalStateException("nonlocal Kafka dependent requires a restart");
                }
            }
        }
        if (dependents.length == 0) return null;
        List<DependencyOwner> owners = new ArrayList<>();
        List<Container> selected = new ArrayList<>();
        List<SavedCallback> callbacks = new ArrayList<>();
        for (String name : dependents) {
            Object bean = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, name);
            if (bean == null) continue;
            Class<?> type = bean.getClass();
            List<Container> owned = new ArrayList<>();
            for (var scope : scopes) for (var c : containers(scope.registry())) {
                Object adapter = messageListener(c.instance());
                Object receiver = listenerBean(adapter);
                if (receiver != bean && AddedKafkaListenerAdapter.ownerOf(receiver) != type) continue;
                if (!adapter.getClass().getName().equals(RECORD_ADAPTER))
                    throw new IllegalStateException("custom dependent Kafka adapter requires a restart");
                requireLifecycle(c);
                if (!c.instance().getClass().getName().equals("org.springframework.kafka.listener.ConcurrentMessageListenerContainer"))
                    throw new IllegalStateException("standard concurrent Kafka container required");
                if (!Boolean.TRUE.equals(c.instance().getClass().getMethod("isRunning").invoke(c.instance())))
                    throw new IllegalStateException("dependent Kafka consumer is inactive or shutting down: " + c.id());
                Object handler = Reflect.readField(adapter, "handlerMethod");
                Object invoker = Reflect.readField(handler, "invokerHandlerMethod");
                if (invoker == null || Reflect.readField(handler, "delegatingHandler") != null)
                    throw new IllegalStateException("single-method Kafka callback required");
                Method method = (Method) invoker.getClass().getMethod("getMethod").invoke(invoker);
                Object metadata = Arrays.stream(method.getDeclaredAnnotations())
                        .filter(a -> a.annotationType().getName().equals("org.springframework.kafka.annotation.KafkaListener"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("literal Kafka callback metadata required"));
                String id = (String) ((java.lang.annotation.Annotation) metadata).annotationType().getMethod("id").invoke(metadata);
                if (!id.equals(c.id()) || id.isBlank() || id.contains("${") || id.contains("#{"))
                    throw new IllegalStateException("dependent Kafka callback requires a stable literal id");
                requireFactory(scope, (String) ((java.lang.annotation.Annotation) metadata).annotationType().getMethod("containerFactory").invoke(metadata));
                if (method.getReturnType() != void.class) throw new IllegalStateException("void dependent Kafka callbacks required");
                String factoryName = (String) ((java.lang.annotation.Annotation) metadata).annotationType().getMethod("containerFactory").invoke(metadata);
                if (factoryName.isEmpty()) factoryName = (String) Reflect.readField(scope.processor(), "defaultContainerFactoryBeanName");
                Object dependencyFactory = SpringBeans.getBean(scope.context(), factoryName);
                Object actualProperties = c.instance().getClass().getMethod("getContainerProperties").invoke(c.instance());
                Object templateProperties = dependencyFactory.getClass().getMethod("getContainerProperties").invoke(dependencyFactory);
                for (Object properties : List.of(actualProperties, templateProperties)) {
                    if (!Set.of("RECORD", "BATCH").contains(String.valueOf(properties.getClass().getMethod("getAckMode").invoke(properties)))
                            || properties.getClass().getMethod("getTransactionManager").invoke(properties) != null)
                        throw new IllegalStateException("nontransactional RECORD or BATCH acknowledgment required for Kafka callback/factory");
                    if ("3.2.4".equals(scope.processor().getClass().getPackage().getImplementationVersion())
                            && properties.getClass().getMethod("getKafkaAwareTransactionManager").invoke(properties) != null)
                        throw new IllegalStateException("transactional Kafka callback/factory requires separate research");
                }
                boolean added = receiver != bean;
                if (added && (!receiver.getClass().isHidden()
                        || !(Reflect.readField(receiver, com.onurkat.reclazz.bootstrap.InjectedNames.PREFIX + "target") instanceof Supplier<?> current)
                        || current.get() != bean))
                    throw new IllegalStateException("dependent Kafka delegate does not own the current singleton");
                if (!added && method.getDeclaringClass() != type)
                    throw new IllegalStateException("inherited dependent Kafka callback requires a restart");
                callbacks.add(new SavedCallback(scope, c, name, type, receiver, method, metadata, added));
                owned.add(c);
            }
            if (!owned.isEmpty()) {
                var cache = SpringConstructorDependencies.prepare(contexts, context, name);
                if (cache == null || type.getSuperclass() != Object.class || type.getInterfaces().length != 0
                        || !plainCallbacks(type)
                        || !Arrays.equals(new String[]{name}, SpringBeans.beanNamesForType(context, type))
                        || ((String[]) factory.getClass().getMethod("getDependentBeans", String.class).invoke(factory, name)).length != 0
                        || !Set.of((String[]) factory.getClass().getMethod("getDependenciesForBean", String.class).invoke(factory, name)).equals(Set.of(root)))
                    throw new IllegalStateException("dependent Kafka owner requires one plain local leaf constructor: " + name);
                owners.add(new DependencyOwner(name, bean, cache));
                selected.addAll(owned);
            }
        }
        if (owners.isEmpty()) return null;
        for (var scope : scopes) {
            if (!Set.of("2.9.13", "3.2.4").contains(scope.processor().getClass().getPackage().getImplementationVersion()))
                throw new IllegalStateException("unverified Kafka processor version");
            if (Reflect.readField(scope.processor(), "enhancer") != null
                    || SpringBeans.beanNamesForType(scope.context(), "org.springframework.kafka.retrytopic.RetryTopicConfiguration").length != 0)
                throw new IllegalStateException("Kafka enhancers/retry topics require a restart");
        }
        if (owners.size() != 1 || selected.size() != 1) throw new IllegalStateException("one Kafka leaf owner/callback required in this stage");
        if (contexts.size() != 1 || contexts.get(0) != context || scopes.size() != 1
                || scopes.get(0).context() != context || owners.size() != dependents.length)
            throw new IllegalStateException("Kafka dependency refresh requires one root context and only direct Kafka leaf owners");
        for (String other : List.of("org.springframework.jms.annotation.JmsListenerAnnotationBeanPostProcessor",
                "org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor")) {
            try {
                Class<?> type = Class.forName(other, false, factory.getClass().getClassLoader());
                String[] names = (String[]) factory.getClass().getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class)
                        .invoke(factory, type, true, false);
                if (names.length != 0) throw new IllegalStateException("mixed broker contexts require a restart for Kafka dependency refresh");
            } catch (ClassNotFoundException absent) { /* Optional module absent. */ }
        }
        Object rootBean = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, root);
        Object raw = factory.getClass().getMethod("getBeanDefinition", String.class).invoke(factory, root);
        if (rootBean == null || !plainRoot(raw, rootBean)
                || ((String[]) factory.getClass().getMethod("getDependenciesForBean", String.class).invoke(factory, root)).length != 0)
            throw new IllegalStateException("Kafka dependency refresh requires a plain directly constructed helper");
        for (var scope : scopes) for (var c : containers(scope.registry())) {
            if (AddedKafkaListenerAdapter.ownerOf(listenerBean(messageListener(c.instance()))) == rootBean.getClass())
                throw new IllegalStateException("a Kafka consumer cannot also be the dependency root");
        }
        Object merged = factory.getClass().getMethod("getMergedBeanDefinition", String.class).invoke(factory, root);
        if (!(Reflect.readField(scopes.get(0).processor(), "nonAnnotatedClasses") instanceof Set<?>))
            throw new IllegalStateException("Kafka negative scan cache is inaccessible");
        return new DependencyRefresh(List.copyOf(contexts), context, factory, root, rootBean, merged,
                List.copyOf(owners), List.copyOf(selected), List.copyOf(callbacks));
    }

    private static boolean plainCallbacks(Class<?> type) {
        for (var field : type.getDeclaredFields()) for (var annotation : field.getDeclaredAnnotations())
            if (!annotation.annotationType().getName().equals("java.lang.Deprecated")) return false;
        for (var a : type.getDeclaredAnnotations())
            if (!Set.of("org.springframework.stereotype.Component", "org.springframework.stereotype.Service").contains(a.annotationType().getName())) return false;
        for (var method : type.getDeclaredMethods()) for (var a : method.getDeclaredAnnotations()) {
            String name = a.annotationType().getName();
            if (!name.equals("org.springframework.kafka.annotation.KafkaListener")
                    && !name.equals("org.springframework.messaging.handler.annotation.SendTo")
                    && !name.equals("java.lang.Deprecated")) return false;
        }
        return true;
    }

    private static boolean plainRoot(Object definition, Object bean) throws Exception {
        Class<?> type = bean.getClass();
        if (type.getSuperclass() != Object.class || type.getInterfaces().length != 0
                || type.getDeclaredConstructors().length != 1 || !plainCallbacks(type) || declaresKafka(type, null)) return false;
        if (!Set.of("org.springframework.beans.factory.support.RootBeanDefinition",
                "org.springframework.beans.factory.support.GenericBeanDefinition",
                "org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition",
                "org.springframework.context.annotation.ScannedGenericBeanDefinition",
                "org.springframework.context.support.GenericApplicationContext$ClassDerivedBeanDefinition")
                .contains(definition.getClass().getName())) return false;
        if (!type.getName().equals(definition.getClass().getMethod("getBeanClassName").invoke(definition))) return false;
        for (String getter : List.of("getParentName", "getFactoryBeanName", "getFactoryMethodName", "getInstanceSupplier",
                "getInitMethodName", "getDestroyMethodName"))
            if (definition.getClass().getMethod(getter).invoke(definition) != null) return false;
        for (String getter : List.of("isAbstract", "hasConstructorArgumentValues", "hasMethodOverrides"))
            if (!Boolean.FALSE.equals(definition.getClass().getMethod(getter).invoke(definition))) return false;
        return Boolean.TRUE.equals(definition.getClass().getMethod("isSingleton").invoke(definition))
                && definition.getClass().getMethod("getQualifiers").invoke(definition) instanceof Set<?> set && set.isEmpty();
    }

    private record DependencyOwner(String name, Object bean, SpringConstructorDependencies.Prepared cache) { }
    private record SavedCallback(Infrastructure scope, Container container, String name, Class<?> type,
                                 Object receiver, Method method, Object metadata, boolean added) { }

    static final class DependencyRefresh {
        private final List<Object> contexts;
        private final Object context, factory, rootBean, merged;
        private final String root;
        private final List<DependencyOwner> owners;
        private final List<Container> selected;
        private final List<SavedCallback> callbacks;

        private DependencyRefresh(List<Object> contexts, Object context, Object factory, String root, Object rootBean,
                Object merged, List<DependencyOwner> owners, List<Container> selected, List<SavedCallback> callbacks) {
            this.contexts = contexts; this.context = context; this.factory = factory; this.root = root;
            this.rootBean = rootBean; this.merged = merged; this.owners = owners; this.selected = selected; this.callbacks = callbacks;
        }

        java.util.Set<String> refreshedNames() {
            var names = new java.util.LinkedHashSet<String>();
            names.add(root);
            for (var owner : owners) names.add(owner.name());
            return java.util.Set.copyOf(names);
        }

        Object[] recreate() throws Exception {
            var fresh = prepareDependencyRefresh(contexts, context, root);
            if (fresh == null || fresh.factory != factory || fresh.rootBean != rootBean || fresh.merged != merged
                    || !fresh.selected.equals(selected) || fresh.owners.size() != owners.size())
                throw new IllegalStateException("Kafka dependency ownership changed after preflight");
            for (int i = 0; i < owners.size(); i++) {
                var a = owners.get(i); var b = fresh.owners.get(i);
                if (!a.name().equals(b.name()) || a.bean() != b.bean() || a.cache().merged() != b.cache().merged())
                    throw new IllegalStateException("Kafka dependent singleton changed after preflight");
            }
            // Timeout leaves helper/owner singletons intact. An inactive retirement
            // is refused on later saves, not started concurrently a second time.
            try { stopAndRemove(selected); }
            catch (Exception failure) {
                RestartLedger.note(root, "dependent Kafka shutdown incomplete; helper and owner singletons retained; restart required");
                throw failure;
            }
            try {
                for (var owner : owners) owner.cache().apply();
                for (var callback : callbacks)
                    ((Set<?>) Reflect.readField(callback.scope().processor(), "nonAnnotatedClasses")).remove(callback.type());
                Object[] result = SpringBeanReloader.destroyAndRefreshBean(context, root);
                for (var owner : owners) context.getClass().getMethod("getBean", String.class).invoke(context, owner.name());
                for (var callback : callbacks) if (callback.added()) {
                    Object current = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, callback.name());
                    SpringKafkaListenerExpressions.register(callback.scope().processor(),
                            Class.forName("org.springframework.kafka.annotation.KafkaListener", false, callback.type().getClassLoader()),
                            callback.metadata(), callback.method(), callback.receiver(), current, callback.name());
                }
                for (var callback : callbacks) {
                    Object container = container(callback.scope().registry(), callback.container().id());
                    Object current = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, callback.name());
                    if (container == null || container == callback.container().instance()
                            || !Boolean.TRUE.equals(container.getClass().getMethod("isRunning").invoke(container))
                            || current == null
                            || current.getClass() != callback.type()
                            || listenerBean(messageListener(container)) != (callback.added() ? callback.receiver() : current))
                        throw new IllegalStateException("Kafka dependent callback was not restored: " + callback.container().id());
                }
                ReloadEffects.note("dependent Kafka consumers re-created");
                return result;
            } catch (Exception | LinkageError failure) {
                // No rollback of application work. Retire only this attempt's known
                // callback IDs; unrelated registry entries are never cleared.
                List<Container> created = new ArrayList<>();
                for (var callback : callbacks) {
                    Object value = container(callback.scope().registry(), callback.container().id());
                    if (value != null && value != callback.container().instance()
                            && AddedKafkaListenerAdapter.ownerOf(listenerBean(messageListener(value))) == callback.type())
                        created.add(new Container(callback.scope().registry(), callback.container().id(), value));
                }
                try { stopAndRemove(created); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                RestartLedger.note(root, "dependent Kafka refresh partially applied; restart required");
                throw failure;
            }
        }
    }

    private static void report(Class<?> type, String reason) {
        StatusReporter.warn("@KafkaListener reload for " + type.getName() + " could not apply: " + reason);
        RestartLedger.note(type.getName(), "Kafka listener declaration not applied: " + reason);
    }
}
