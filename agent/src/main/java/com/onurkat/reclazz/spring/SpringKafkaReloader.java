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
                Method process = Reflect.findMethod(scope.processor().getClass(), "processKafkaListener", annotation, Method.class, Object.class, String.class);
                if (process == null) throw new IllegalStateException("Kafka listener processor method is inaccessible");
                for (int i = 0; i < delegate.methods().size(); i++) {
                    Method method = delegate.methods().get(i);
                    Object metadata = Arrays.stream(method.getDeclaredAnnotations()).filter(a -> a.annotationType() == annotation).findFirst().orElseThrow();
                    try {
                        process.invoke(scope.processor(), metadata, method, delegate.bean(), name);
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
        Object factory = SpringBeans.getBean(scope.context(), name);
        if (factory == null || !factory.getClass().getName().equals("org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory"))
            throw new IllegalStateException("standard ConcurrentKafkaListenerContainerFactory required: " + name);
        for (String field : List.of("recordFilterStrategy", "retryTemplate", "containerCustomizer", "batchToRecordAdapter")) {
            var metadata = Reflect.findField(factory.getClass(), field);
            if (metadata == null || metadata.get(factory) != null)
                throw new IllegalStateException("Kafka factory " + field + " is unsupported for added listeners");
        }
        if (Boolean.TRUE.equals(factory.getClass().getMethod("isBatchListener").invoke(factory)))
            throw new IllegalStateException("batch Kafka factories require a restart for added listeners");
    }

    private List<Infrastructure> infrastructures() throws Exception {
        List<Infrastructure> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object context : platform.getAllApplicationContexts()) {
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
    private static void report(Class<?> type, String reason) {
        StatusReporter.warn("@KafkaListener reload for " + type.getName() + " could not apply: " + reason);
        RestartLedger.note(type.getName(), "Kafka listener declaration not applied: " + reason);
    }
}
