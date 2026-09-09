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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Lifecycle coordination for standard Spring Rabbit containers around bean recreation. */
public final class SpringRabbitReloader {
    private static final String PROCESSOR = "org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor";
    private static final String REGISTRY = "org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry";
    private static final String ADAPTER = "org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter";
    private final PlatformContext platform;
    private static final class OwnerState { volatile boolean registered; }
    private final ClassValue<OwnerState> owners = new ClassValue<>() {
        @Override protected OwnerState computeValue(Class<?> type) { return new OwnerState(); }
    };
    private final long stopTimeoutMillis;
    // Completed FutureTask clears its callable, so finished work does not retain
    // a weak key. In-flight shutdown owns its container until resources close.
    private final Map<Object, FutureTask<Void>> retirements = new WeakHashMap<>();
    public SpringRabbitReloader(PlatformContext platform) { this(platform, 30_000); }
    SpringRabbitReloader(PlatformContext platform, long stopTimeoutMillis) {
        this.platform = platform;
        this.stopTimeoutMillis = stopTimeoutMillis;
    }
    private record Infrastructure(Object context, Object processor, Object registry) { }
    private record Container(Object registry, String id, Object instance) { }

    public synchronized boolean beforeBeanRefresh(Class<?> type) {
        return beforeBeanRefresh(type, null);
    }

    public synchronized boolean beforeBeanRefresh(Class<?> type, byte[] bytes) {
        return beforeBeanRefresh(type, bytes, Set.of());
    }

    public synchronized boolean beforeBeanRefresh(Class<?> type, byte[] bytes, Set<String> added) {
        return beforeBeanRefresh(type, bytes, added, null, null);
    }

    synchronized boolean beforeBeanRefresh(Class<?> type, byte[] bytes, SpringKafkaReloader kafka, SpringJmsReloader jms) {
        return beforeBeanRefresh(type, bytes, Set.of(), kafka, jms);
    }

    synchronized boolean beforeBeanRefresh(Class<?> type, byte[] bytes, Set<String> added,
                                            SpringKafkaReloader kafka, SpringJmsReloader jms) {
        boolean relevant = owners.get(type).registered;
        try {
            relevant |= declaresRabbit(type, bytes);
            List<Infrastructure> scopes = infrastructures();
            List<Container> selected = new ArrayList<>();
            for (var scope : scopes) {
                Object cache = Reflect.readField(scope.processor(), "typeCache");
                if (!(cache instanceof Map<?,?>)) throw new IllegalStateException("Rabbit negative scan cache is inaccessible");
                for (var c : containers(scope.registry())) {
                    Object listener = messageListener(c.instance());
                    Object bean = listenerBean(listener);
                    if (AddedRabbitListenerAdapter.ownerOf(bean) != type && (bean == null || !type.isInstance(bean))) continue;
                    relevant = true;
                    owners.get(type).registered = true;
                    if (!listener.getClass().getName().equals(ADAPTER))
                        throw new IllegalStateException("custom Rabbit listener adapter for " + c.id());
                    if (AddedRabbitListenerAdapter.ownerOf(bean) != type)
                        throw new IllegalStateException("proxy or subclass Rabbit listener receiver for " + c.id());
                    // Validate every selected entry before stopping any of them.
                    requireLifecycle(c);
                    selected.add(c);
                }
            }
            // Both brokers would otherwise retire consumers in sequence. Refuse
            // this unsupported combination before either one mutates its registry.
            if (relevant && ((kafka != null && kafka.hasListeners(type, bytes)) || (jms != null && jms.hasListeners(type, bytes))))
                throw new IllegalStateException("mixed Rabbit/Kafka/JMS listener beans require a restart");
            if (relevant) {
                requireTracking(type.getClassLoader());
                if (bytes != null) {
                    // Spring re-scans original methods itself, including generated
                    // IDs and options that the added-method delegate cannot carry.
                    // Apply that delegate's subset only to the supplied additions.
                    var metadata = AddedRabbitListenerAdapter.inspect(bytes, added);
                    if (!metadata.refused().isEmpty()) throw new IllegalStateException(String.join("; ", metadata.refused()));
                    for (var scope : scopes) {
                        if (SpringBeans.beanNamesForType(scope.context(), type).length == 0) continue;
                        for (var method : metadata.methods()) requireFactory(scope, method);
                        for (String id : metadata.ids()) {
                            Object existing = container(scope.registry(), id);
                            if (existing != null && selected.stream().noneMatch(c -> c.instance() == existing))
                                throw new IllegalStateException("listener id already belongs to an existing container: " + id);
                        }
                    }
                }
            }
            stopAndRemove(selected);
            for (var scope : scopes) ((Map<?,?>) Reflect.readField(scope.processor(), "typeCache")).remove(type);
            return true;
        } catch (Throwable failure) {
            // An optional unsupported Rabbit stack must not stop an unrelated
            // controller/service from following its ordinary reload path.
            if (!relevant) return true;
            report(type, "bean recreation deferred: " + Failures.describe(failure));
            return false;
        }
    }

    public synchronized boolean reloadRabbitListeners(Class<?> type, Set<String> added, byte[] bytes) {
        var plan = AddedRabbitListenerAdapter.inspect(bytes, added);
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
            requireTracking(type.getClassLoader());
            for (var scope : infrastructures()) {
                String[] names = SpringBeans.beanNamesForType(scope.context(), type);
                if (names.length == 0) continue;
                found = true;
                if (names.length != 1) throw new IllegalStateException("literal listener IDs require one matching bean per context");
                Object factory = SpringBeans.getBeanFactory(scope.context());
                String name = names[0];
                if (!(Boolean) factory.getClass().getMethod("isSingleton", String.class).invoke(factory, name))
                    throw new IllegalStateException("only singleton Rabbit listener beans are supported");
                Supplier<Object> current = currentSingleton(factory, name, type);
                current.get();
                if (type.getSuperclass() != Object.class || type.getInterfaces().length != 0)
                    throw new IllegalStateException("listener inheritance/interfaces are unsupported");
                for (String id : plan.ids()) if (container(scope.registry(), id) != null)
                    throw new IllegalStateException("listener id already belongs to an existing container: " + id);
                for (var method : plan.methods()) requireFactory(scope, method);
                var delegate = AddedRabbitListenerAdapter.create(type, current, plan);
                Class<?> annotation = Class.forName("org.springframework.amqp.rabbit.annotation.RabbitListener", false, type.getClassLoader());
                Method process = Reflect.findMethod(scope.processor().getClass(), "processAmqpListener", annotation, Method.class, Object.class, String.class);
                if (process == null) throw new IllegalStateException("Rabbit listener processor method is inaccessible");
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
                    if (registered == null) throw new IllegalStateException("Rabbit processor did not register " + plan.ids().get(i));
                    if (!messageListener(registered).getClass().getName().equals(ADAPTER))
                        throw new IllegalStateException("custom listener adapters require a restart");
                    changed = true;
                }
            }
            if (!found) throw new IllegalStateException("no matching singleton with a Rabbit listener processor was found");
        } catch (Throwable failure) {
            try { stopAndRemove(created); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            report(type, Failures.describe(failure));
            return false;
        }
        if (changed) ReloadEffects.note("added @RabbitListener registered");
        return changed;
    }

    private static boolean declaresRabbit(Class<?> type, byte[] bytes) {
        String prefix = "org.springframework.amqp.rabbit.annotation.";
        if (bytes == null) {
            for (var a : type.getDeclaredAnnotations()) if (a.annotationType().getName().startsWith(prefix)) return true;
            for (var m : type.getDeclaredMethods()) for (var a : m.getDeclaredAnnotations())
                if (a.annotationType().getName().startsWith(prefix)) return true;
            return false;
        }
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG);
        String descriptor = "Lorg/springframework/amqp/rabbit/annotation/";
        if (node.visibleAnnotations != null) for (var a : node.visibleAnnotations) if (a.desc.startsWith(descriptor)) return true;
        for (var m : node.methods) if (m.visibleAnnotations != null)
            for (var a : m.visibleAnnotations) if (a.desc.startsWith(descriptor)) return true;
        return false;
    }

    private static void requireFactory(Infrastructure scope, org.objectweb.asm.tree.MethodNode method) throws Exception {
        Object defaultName = Reflect.readField(scope.processor(), "defaultContainerFactoryBeanName");
        if (!(defaultName instanceof String name)) throw new IllegalStateException("Rabbit default container factory is inaccessible");
        for (var a : method.visibleAnnotations) if (a.desc.equals(AddedRabbitListenerAdapter.RABBIT) && a.values != null)
            for (int i = 0; i < a.values.size(); i += 2)
                if (a.values.get(i).equals("containerFactory")) name = (String) a.values.get(i + 1);
        Object factory = SpringBeans.getBean(scope.context(), name);
        if (factory == null || !factory.getClass().getName().equals("org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory"))
            throw new IllegalStateException("standard SimpleRabbitListenerContainerFactory required: " + name);
        for (String field : List.of("transactionManager", "taskExecutor", "containerCustomizer")) requireEmpty(factory, field);
        requireNoAdvice(factory);
        for (String field : List.of("batchListener", "consumerBatchEnabled")) {
            var metadata = Reflect.findField(factory.getClass(), field);
            if (metadata == null || Boolean.TRUE.equals(metadata.get(factory)))
                throw new IllegalStateException("Rabbit batch factories require a restart");
        }
        var ack = Reflect.findField(factory.getClass(), "acknowledgeMode");
        if (ack == null || (ack.get(factory) != null && !ack.get(factory).toString().equals("AUTO")))
            throw new IllegalStateException("Rabbit AUTO acknowledgment required");
    }
    private static void requireEmpty(Object target, String field) throws Exception {
        var metadata = Reflect.findField(target.getClass(), field);
        if (metadata == null || metadata.get(target) != null)
            throw new IllegalStateException("Rabbit " + field + " is unsupported");
    }
    private static void requireNoAdvice(Object target) throws Exception {
        var field = Reflect.findField(target.getClass(), "adviceChain");
        if (field == null || (field.get(target) != null && java.lang.reflect.Array.getLength(field.get(target)) != 0))
            throw new IllegalStateException("Rabbit container advice requires a restart");
    }

    private List<Infrastructure> infrastructures() throws Exception {
        List<Infrastructure> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object context : platform.getAllApplicationContexts()) {
            String[] names = SpringBeans.beanNamesForType(context, PROCESSOR);
            if (names.length == 0) continue;
            if (names.length != 1) throw new IllegalStateException("multiple Rabbit listener processors are unsupported");
            Object processor = SpringBeans.getBean(context, names[0]);
            if (processor == null || !processor.getClass().getName().equals(PROCESSOR))
                throw new IllegalStateException("custom Rabbit listener processors are unsupported");
            Object registrar = Reflect.readField(processor, "registrar");
            if (registrar == null) throw new IllegalStateException("Rabbit registrar is inaccessible");
            Object registry = registrar.getClass().getMethod("getEndpointRegistry").invoke(registrar);
            if (registry == null || !registry.getClass().getName().equals(REGISTRY))
                throw new IllegalStateException("standard Rabbit endpoint registry is required");
            if (seen.add(registry)) result.add(new Infrastructure(context, processor, registry));
        }
        return result;
    }
    private static Object container(Object registry, String id) throws Exception {
        return registry.getClass().getMethod("getListenerContainer", String.class).invoke(registry, id);
    }
    private static List<Container> containers(Object registry) throws Exception {
        Object ids = registry.getClass().getMethod("getListenerContainerIds").invoke(registry);
        if (!(ids instanceof Set<?> set)) throw new IllegalStateException("Rabbit container IDs are inaccessible");
        List<Container> result = new ArrayList<>();
        for (Object id : new ArrayList<>(set)) {
            Object value = container(registry, (String) id);
            if (value != null) result.add(new Container(registry, (String) id, value));
        }
        return result;
    }
    private static Object messageListener(Object container) throws Exception {
        return container.getClass().getMethod("getMessageListener").invoke(container);
    }
    private static Object listenerBean(Object listener) throws Exception {
        if (listener == null) return null;
        Object handler = Reflect.readField(listener, "handlerAdapter");
        return handler == null ? null : handler.getClass().getMethod("getBean").invoke(handler);
    }
    private static Map<?,?> registryMap(Object registry) {
        Object value = Reflect.readField(registry, "listenerContainers");
        if (!(value instanceof Map<?,?> map)) throw new IllegalStateException("Rabbit registry map is inaccessible");
        return map;
    }
    private static void requireLifecycle(Container c) throws Exception {
        if (!c.instance().getClass().getName().equals("org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer"))
            throw new IllegalStateException("standard SimpleMessageListenerContainer required: " + c.id());
        requireTracking(c.instance().getClass().getClassLoader());
        if (!c.instance().getClass().getMethod("getAcknowledgeMode").invoke(c.instance()).toString().equals("AUTO")
                || !Boolean.FALSE.equals(Reflect.readField(c.instance(), "consumerBatchEnabled")))
            throw new IllegalStateException("Rabbit AUTO single-message containers required");
        Object executor = Reflect.readField(c.instance(), "taskExecutor");
        if (executor == null || !executor.getClass().getName().equals("org.springframework.core.task.SimpleAsyncTaskExecutor"))
            throw new IllegalStateException("Rabbit custom task executor is unsupported");
        requireNoAdvice(c.instance());
        requireEmpty(c.instance(), "transactionManager");
        registryMap(c.registry());
        for (String method : List.of("destroy", "isRunning", "isActive", "getActiveConsumerCount"))
            c.instance().getClass().getMethod(method);
    }
    private static void requireTracking(ClassLoader loader) throws Exception {
        Class<?> worker = Class.forName("org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer$AsyncMessageProcessingConsumer", true, loader);
        if (!com.onurkat.reclazz.bootstrap.RabbitConsumerBridge.isHooked(worker))
            throw new IllegalStateException("Rabbit worker tracking unavailable; start with -javaagent");
    }
    private void stopAndRemove(List<Container> selected) throws Exception {
        for (var c : selected) requireLifecycle(c);
        for (var c : selected) com.onurkat.reclazz.bootstrap.RabbitConsumerBridge.retire(c.instance());
        for (var c : selected) {
            FutureTask<Void> work = retirements.get(c.instance());
            if (work == null) {
                Object instance = c.instance();
                work = new FutureTask<>(() -> {
                    // Spring can return after force-closing the channel while
                    // work still runs. The separately tracked worker must also finish.
                    instance.getClass().getMethod("destroy").invoke(instance);
                    return null;
                });
                retirements.put(instance, work);
                var thread = new Thread(work, "reclazz-rabbit-shutdown");
                thread.setDaemon(true);
                thread.start();
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stopTimeoutMillis);
        for (var c : selected) {
            try {
                retirements.get(c.instance()).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (!com.onurkat.reclazz.bootstrap.RabbitConsumerBridge.awaitStopped(c.instance(), deadline))
                    throw new java.util.concurrent.TimeoutException("Rabbit worker still executing");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new IllegalStateException("Rabbit consumer shutdown unfinished; registry ownership retained for retry", timeout);
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Rabbit shutdown wait interrupted");
            if ((Boolean) c.instance().getClass().getMethod("isRunning").invoke(c.instance())
                    || (Boolean) c.instance().getClass().getMethod("isActive").invoke(c.instance())
                    || ((Number) c.instance().getClass().getMethod("getActiveConsumerCount").invoke(c.instance())).intValue() != 0)
                throw new IllegalStateException("Rabbit consumer resources remain active: " + c.id());
        }
        for (var c : selected) {
            Map<?,?> map = registryMap(c.registry());
            synchronized (map) {
                if (!map.remove(c.id(), c.instance()))
                    throw new IllegalStateException("Rabbit container ownership changed during shutdown: " + c.id());
            }
            retirements.remove(c.instance());
        }
    }
    private static Supplier<Object> currentSingleton(Object factory, String name, Class<?> type) throws Exception {
        Method get = factory.getClass().getMethod("getSingleton", String.class);
        return () -> {
            try {
                Object bean = get.invoke(factory, name);
                if (bean == null || bean.getClass() != type)
                    throw new IllegalStateException("Rabbit callback requires current plain singleton " + name + "; missing/proxy/subclass receiver refused");
                return bean;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot read Rabbit singleton " + name, failure); }
        };
    }
    private static void report(Class<?> type, String reason) {
        StatusReporter.warn("@RabbitListener reload for " + type.getName() + " could not apply: " + reason);
        RestartLedger.note(type.getName(), "Rabbit listener declaration not applied: " + reason);
    }
}
