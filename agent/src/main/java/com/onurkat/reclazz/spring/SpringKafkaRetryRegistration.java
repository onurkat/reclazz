/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.bootstrap.KafkaRetryRoutes;
import com.onurkat.reclazz.util.Reflect;
import com.onurkat.reclazz.ui.RestartLedger;
import java.lang.reflect.*;
import java.util.*;

/** One native main/retry/DLT graph. No live consumer starts before its destinations exist. */
final class SpringKafkaRetryRegistration {
    private static final String KAFKA = "org.springframework.kafka.";
    private static final String FACTORY = "org.springframework.beans.factory.";
    private static final String TABLE = InjectedNames.PREFIX + "kafkaRetryRoutes";
    private record ContextRoutes(KafkaRetryRoutes table, List<SpringKafkaRetryRegistration> groups) { }
    private final Object factory;
    private final ContextRoutes contextRoutes;
    final Class<?> ownerType;
    final String mainId;
    Method nativeCallback;
    private boolean nativeBaseline;
    private final KafkaRetryRoutes table;
    private final Map<?,?> routes;
    private final Map<String,Object> singletons = new LinkedHashMap<>();
    private final List<SpringKafkaReloader.Container> containers = new ArrayList<>();
    private boolean published;
    private volatile boolean quarantined;

    private SpringKafkaRetryRegistration(Object factory, ContextRoutes contextRoutes, Map<?,?> routes,
            Class<?> ownerType, String mainId) {
        this.factory = factory;
        this.contextRoutes = contextRoutes;
        this.table = contextRoutes.table();
        this.routes = routes;
        this.ownerType = ownerType;
        this.mainId = mainId;
    }

    List<SpringKafkaReloader.Container> containers() { return List.copyOf(containers); }

    static List<SpringKafkaRetryRegistration> groups(Object factory) throws ReflectiveOperationException {
        synchronized (ownershipMutex(factory)) {
            NativeKafkaRetryOwnership.materialize(factory);
            return singleton(factory, TABLE) instanceof ContextRoutes context ? List.copyOf(context.groups()) : List.of();
        }
    }

    static void adopt(Object factory, NativeKafkaRetryOwnership.Receipt receipt, List<SpringKafkaReloader.Container> members)
            throws ReflectiveOperationException {
        var context = table(factory, receipt.resolver(), receipt.version());
        var group = new SpringKafkaRetryRegistration(factory, context, receipt.routes(),
                receipt.method().getDeclaringClass(), receipt.mainId());
        if (!context.table().claimOriginal(receipt.routes())) {
            group.releaseTable();
            return;
        }
        group.nativeCallback = receipt.method();
        group.nativeBaseline = true;
        group.published = true;
        group.singletons.putAll(receipt.products());
        group.containers.addAll(members);
        context.groups().add(group);
    }

    static SpringKafkaRetryRegistration register(Object processor, Class<?> annotation, Object metadata, Object delegate,
            String beanName, Object endpoint, SpringKafkaListenerExpressions.RetryProbe probe) throws ReflectiveOperationException {
        var loader = annotation.getClassLoader();
        var methodEndpoint = loader.loadClass(KAFKA + "config.MethodKafkaListenerEndpoint");
        var populate = Reflect.findMethod(processor.getClass(),"processKafkaListenerAnnotation",methodEndpoint,annotation,
                Object.class,String[].class,Array.newInstance(loader.loadClass(KAFKA + "support.TopicPartitionOffset"),0).getClass());
        if (populate == null) throw new NoSuchMethodException("unmeasured Kafka listener population method");
        var endpointProcessor = loader.loadClass(KAFKA + "retrytopic.RetryTopicConfigurer$EndpointProcessor");
        Object configure = Proxy.newProxyInstance(loader,new Class<?>[]{endpointProcessor},(proxy, method, args) -> {
            if (!method.getName().equals("accept")) throw new UnsupportedOperationException(method.toString());
            return populate.invoke(processor,args[0],metadata,delegate,probe.topics(),probe.partitions());
        });
        String factoryName = (String) annotation.getMethod("containerFactory").invoke(metadata);
        Object resolved = method(processor,"resolve",String.class).invoke(processor,factoryName);
        Object listenerFactory = method(processor,"resolveContainerFactory",annotation,Object.class,String.class)
                .invoke(processor,metadata,resolved,beanName);
        return stage(invoke(processor, "getRetryTopicConfigurer"), configure, endpoint, probe.configuration(),
                invoke(processor, "getEndpointRegistrar"), listenerFactory, (String)field(processor, "defaultContainerFactoryBeanName"),
                AddedKafkaListenerAdapter.ownerOf(delegate));
    }

    static SpringKafkaRetryRegistration stage(Object configurer, Object configure, Object endpoint, Object configuration,
            Object registrar, Object listenerFactory, String defaultFactoryName, Class<?> ownerType)
            throws ReflectiveOperationException {
        ClassLoader loader = configurer.getClass().getClassLoader();
        String version = configurer.getClass().getPackage().getImplementationVersion();
        if (!Set.of("2.9.13","3.2.4").contains(version)) throw new IllegalStateException("unverified Kafka retry version");
        Object factory = field(configurer,"beanFactory");
        Object registry = invoke(registrar,"getEndpointRegistry");
        var configurerType = loader.loadClass(KAFKA + "retrytopic.RetryTopicConfigurer");
        Object destinationProcessor = field(configurer,"destinationTopicProcessor");
        Object resolver = field(destinationProcessor,"destinationTopicResolver");
        var resolverType = loader.loadClass(KAFKA + "retrytopic.DefaultDestinationTopicResolver");
        var processorType = loader.loadClass(KAFKA + "retrytopic.DefaultDestinationTopicProcessor");
        if (configurer.getClass() != configurerType || destinationProcessor.getClass() != processorType || resolver.getClass() != resolverType)
            throw new IllegalStateException("custom Kafka retry configurer or destination processor/resolver requires restart");
        var stagedResolver = resolverType.getConstructor().newInstance();
        var stagedProcessor = processorType.getConstructor(loader.loadClass(KAFKA + "retrytopic.DestinationTopicResolver"))
                .newInstance(stagedResolver);
        var stagedConfigurer = configurerType.getConstructor(loader.loadClass(KAFKA + "retrytopic.DestinationTopicProcessor"),
                loader.loadClass(KAFKA + "retrytopic.ListenerContainerFactoryResolver"),
                loader.loadClass(KAFKA + "retrytopic.ListenerContainerFactoryConfigurer"),
                loader.loadClass(KAFKA + "retrytopic.RetryTopicNamesProviderFactory")).newInstance(stagedProcessor,
                field(configurer,"containerFactoryResolver"), field(configurer,"listenerContainerFactoryConfigurer"),
                field(configurer,"retryTopicNamesProviderFactory"));
        Class<?> beanFactoryType = loader.loadClass(FACTORY + "BeanFactory");
        Object products = loader.loadClass(FACTORY + "support.DefaultListableBeanFactory").getConstructor(beanFactoryType).newInstance(factory);
        configurerType.getMethod("setBeanFactory",beanFactoryType).invoke(stagedConfigurer,products);
        var stagedRegistrar = registrar.getClass().getConstructor().newInstance();
        Class<?> endpointProcessor = loader.loadClass(KAFKA + "retrytopic.RetryTopicConfigurer$EndpointProcessor");
        Class<?> methodEndpoint = loader.loadClass(KAFKA + "config.MethodKafkaListenerEndpoint");
        var factoryType = loader.loadClass(KAFKA + "config.KafkaListenerContainerFactory");
        configurerType.getMethod("processMainAndRetryListeners",endpointProcessor,methodEndpoint,loader.loadClass(KAFKA + "retrytopic.RetryTopicConfiguration"),
                stagedRegistrar.getClass(),factoryType,String.class).invoke(stagedConfigurer,configure,endpoint,
                configuration,stagedRegistrar,listenerFactory,defaultFactoryName);

        Map<?,?> routes = Map.copyOf((Map<?,?>)field(stagedResolver,"sourceDestinationsHolderMap"));
        if (routes.isEmpty()) throw new IllegalStateException("native Kafka configurer produced no retry destinations");
        List<?> descriptors = List.copyOf((List<?>)field(stagedRegistrar,"endpointDescriptors"));
        var endpoints = new LinkedHashMap<String,Object[]>();
        for (Object descriptor : descriptors) {
            Object staged = field(descriptor,"endpoint");
            String id = (String)invoke(staged,"getId");
            if (endpoints.putIfAbsent(id,new Object[]{staged,field(descriptor,"containerFactory")}) != null
                    || SpringKafkaReloader.container(registry,id) != null)
                throw new IllegalStateException("Kafka retry container id already in use: " + id);
        }
        SpringKafkaRetryRegistration registration;
        synchronized (ownershipMutex(factory)) {
            var context = table(factory,resolver,version);
            registration = new SpringKafkaRetryRegistration(factory, context, routes, ownerType, (String)invoke(endpoint, "getId"));
            try {
                context.table().add(routes);
                registration.published = true;
            } catch (RuntimeException rejected) {
                registration.releaseTable();
                throw rejected;
            }
        }
        try {
            for (String name : (String[])invoke(products,"getSingletonNames")) {
                Object product = singleton(products,name);
                method(factory,"registerSingleton",String.class,Object.class).invoke(factory,name,product);
                registration.singletons.put(name,product);
            }
            registration.createBrokerTopics(loader);
            Method register = registry.getClass().getMethod("registerListenerContainer",
                    loader.loadClass(KAFKA + "config.KafkaListenerEndpoint"),factoryType,boolean.class);
            for (var entry : endpoints.entrySet()) {
                String id = entry.getKey();
                var pair = entry.getValue();
                // Native registration may throw after putting the container. Hold the same registration lock
                // while taking the before/after difference so a competing registration cannot become ours.
                if (version.equals("2.9.13")) {
                    synchronized (field(registry,"listenerContainers")) {
                        registration.installContainer(registry,register,id,pair);
                    }
                } else {
                    var lock = (java.util.concurrent.locks.Lock)field(registry,"containersLock");
                    lock.lock();
                    try { registration.installContainer(registry,register,id,pair); }
                    finally { lock.unlock(); }
                }
            }
            var start = method(registry,"startIfNecessary",loader.loadClass(KAFKA + "listener.MessageListenerContainer"));
            for (var c : registration.containers) start.invoke(registry,c.instance());
            synchronized (ownershipMutex(factory)) {
                registration.contextRoutes.groups().add(registration);
            }
            return registration;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            registration.rollback(failure);
            throw failure;
        }
    }

    private void installContainer(Object registry, Method register, String id, Object[] pair)
            throws ReflectiveOperationException {
        if (SpringKafkaReloader.container(registry,id) != null)
            throw new IllegalStateException("Kafka retry container id already in use: " + id);
        Object instance;
        try {
            register.invoke(registry,pair[0],pair[1],false);
        } finally {
            instance = SpringKafkaReloader.container(registry,id);
            if (instance != null) containers.add(new SpringKafkaReloader.Container(registry,id,instance));
        }
        if (instance == null) throw new IllegalStateException("native Kafka registry omitted " + id);
    }

    private void releaseTable() throws ReflectiveOperationException {
        if (singleton(factory,TABLE) == contextRoutes && table.detachIfUnused())
            method(factory,"destroySingleton",String.class).invoke(factory,TABLE);
    }

    private static ContextRoutes table(Object factory,Object resolver,String version) throws ReflectiveOperationException {
        synchronized (ownershipMutex(factory)) {
            Object existing = singleton(factory,TABLE);
            if (existing instanceof ContextRoutes context && context.table().owns(resolver)) return context;
            if (existing != null) throw new IllegalStateException("Kafka retry resolver identity changed");
            KafkaRetryRoutes.beginProbe(resolver);
            boolean observed;
            try {
                var lookup = version.equals("2.9.13") ? method(resolver,"getDestinationTopicByName",String.class)
                        : method(resolver,"getDestinationTopicByName",String.class,String.class);
                Object[] keys = new String[lookup.getParameterCount()];
                Arrays.fill(keys,TABLE);
                try { lookup.invoke(resolver,keys); } catch (InvocationTargetException missing) { /* lookup-only probe */ }
            } finally { observed = KafkaRetryRoutes.endProbe(); }
            if (!observed) throw new IllegalStateException("matching Kafka retry-topic configuration requires a restart; resolver snapshot hook unavailable");
            var table = new KafkaRetryRoutes(resolver,(Map<?,?>)field(resolver,"sourceDestinationsHolderMap"));
            var context = new ContextRoutes(table, new ArrayList<>());
            method(factory,"registerSingleton",String.class,Object.class).invoke(factory,TABLE,context);
            return context;
        }
    }

    private void createBrokerTopics(ClassLoader loader) throws ReflectiveOperationException {
        if (singletons.isEmpty()) return;
        Class<?> adminType = loader.loadClass(KAFKA + "core.KafkaAdmin");
        Class<?> topicType = loader.loadClass("org.apache.kafka.clients.admin.NewTopic");
        var admins = (Map<?,?>)method(factory,"getBeansOfType",Class.class).invoke(factory,adminType);
        for (Object admin : admins.values()) {
            if (!Boolean.TRUE.equals(field(admin,"autoCreate"))) continue;
            @SuppressWarnings("unchecked") var filter = (java.util.function.Predicate<Object>)invoke(admin,"getCreateOrModifyTopic");
            var selected = singletons.values().stream().filter(topicType::isInstance).filter(filter).toList();
            if (selected.isEmpty()) continue;
            Object array = Array.newInstance(topicType,selected.size());
            for (int i=0;i<selected.size();i++) Array.set(array,i,selected.get(i));
            method(admin,"createOrModifyTopics",array.getClass()).invoke(admin,array);
        }
    }

    void rollback(Throwable failure) {
        try {
            SpringKafkaReloader.stopAndRemove(containers);
            removeProducts();
        } catch (Exception | LinkageError cleanup) {
            quarantine(cleanup);
            failure.addSuppressed(cleanup);
        }
    }

    void quarantine(Throwable failure) {
        synchronized (ownershipMutex(factory)) {
            quarantined = true;
            if (!contextRoutes.groups().contains(this)) contextRoutes.groups().add(this);
        }
        RestartLedger.note(ownerType.getName(), "Kafka retry group " + mainId
                + " cleanup incomplete; restart required: " + com.onurkat.reclazz.ui.Failures.describe(failure));
    }

    void verifyOwnership() throws ReflectiveOperationException {
        if (quarantined) throw new IllegalStateException("Kafka retry group " + mainId + " cleanup incomplete; restart required");
        if (nativeCallback != null) {
            synchronized (ownershipMutex(factory)) {
                for (var other : contextRoutes.groups())
                    if (other != this && nativeCallback.equals(other.nativeCallback))
                        throw new IllegalStateException("Kafka startup retry listeners " + mainId + " and " + other.mainId
                                + " share the same callback; restart required");
            }
        }
        for (var member : containers)
            if (SpringKafkaReloader.container(member.registry(), member.id()) != member.instance())
                throw new IllegalStateException("Kafka retry container ownership changed: " + member.id());
        verifyProducts();
    }

    private void verifyProducts() throws ReflectiveOperationException {
        for (var entry : singletons.entrySet()) {
            if (singleton(factory, entry.getKey()) != entry.getValue())
                throw new IllegalStateException("Kafka retry singleton ownership changed: " + entry.getKey());
        }
    }

    void removeProducts() throws ReflectiveOperationException {
        synchronized (ownershipMutex(factory)) {
            verifyProducts();
            if (published) {
                table.remove(routes, nativeBaseline);
                published = false;
            }
            var iterator = singletons.keySet().iterator();
            while (iterator.hasNext()) {
                method(factory, "destroySingleton", String.class).invoke(factory, iterator.next());
                iterator.remove();
            }
            NativeKafkaRetryOwnership.retired(factory, nativeCallback, mainId);
            contextRoutes.groups().remove(this);
            releaseTable();
        }
    }

    private static final Map<Object,Object> ownershipLocks = new WeakHashMap<>();

    static Object ownershipMutex(Object factory) {
        synchronized (ownershipLocks) {
            return ownershipLocks.computeIfAbsent(factory, ignored -> new Object());
        }
    }

    private static Object singleton(Object factory,String name) throws ReflectiveOperationException {
        return method(factory,"getSingleton",String.class).invoke(factory,name);
    }
    private static Object field(Object object,String name) {
        Object value = Reflect.readField(object,name);
        if (value == null) throw new IllegalStateException("Kafka retry field unavailable: " + name);
        return value;
    }
    private static Object invoke(Object object,String name) throws ReflectiveOperationException {
        return method(object,name).invoke(object);
    }
    private static Method method(Object object,String name,Class<?>... parameters) throws NoSuchMethodException {
        Method value = Reflect.findMethod(object.getClass(),name,parameters);
        if (value == null) throw new NoSuchMethodException(name);
        return value;
    }
}
