/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.NativeListenerSetup;
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

/** Lifecycle coordination for standard Spring JMS containers around bean recreation. */
public final class SpringJmsReloader {
    private static final String PROCESSOR = "org.springframework.jms.annotation.JmsListenerAnnotationBeanPostProcessor";
    private static final String REGISTRY = "org.springframework.jms.config.JmsListenerEndpointRegistry";
    private static final String ADAPTER = "org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter";
    private final PlatformContext platform;
    private static final class OwnerState { volatile boolean registered; }
    private final ClassValue<OwnerState> owners = new ClassValue<>() {
        @Override protected OwnerState computeValue(Class<?> type) { return new OwnerState(); }
    };
    private final long stopTimeoutMillis;
    // Completed FutureTask clears its callable, so finished work does not retain
    // a weak key. In-flight shutdown owns its container until resources close.
    private final Map<Object, FutureTask<Void>> retirements = new WeakHashMap<>();
    public SpringJmsReloader(PlatformContext platform) { this(platform, 30_000); }
    SpringJmsReloader(PlatformContext platform, long stopTimeoutMillis) {
        this.platform = platform;
        this.stopTimeoutMillis = stopTimeoutMillis;
    }
    private record Infrastructure(Object context, Object processor, Object registry) { }
    private record Container(Object registry, String id, Object instance) { }

    /** Read-only: whether this bean owns or declares JMS consumers. */
    synchronized boolean hasListeners(Class<?> type, byte[] bytes) {
        if (owners.get(type).registered || declaresJms(type, bytes)) return true;
        try {
            for (var scope : infrastructures()) for (var c : containers(scope.registry())) {
                Object bean = registrationBean(c.instance(), scope.processor());
                if (AddedJmsListenerAdapter.ownerOf(bean) == type || (bean != null && type.isInstance(bean))) return true;
            }
            return false;
        } catch (Throwable unavailable) {
            // Uncertain JMS ownership is treated as ownership, so the bean is
            // recreated as a listener owner rather than kept.
            return true;
        }
    }

    public synchronized boolean beforeBeanRefresh(Class<?> type) {
        return beforeBeanRefresh(type, null);
    }

    public synchronized boolean beforeBeanRefresh(Class<?> type, byte[] bytes) {
        return prepareBeanRefresh(type, bytes).commit();
    }

    /** Validates this broker's part of a bean refresh; nothing retires until commit. */
    synchronized ListenerRetirement prepareBeanRefresh(Class<?> type, byte[] bytes) {
        boolean relevant = owners.get(type).registered;
        try {
            relevant |= declaresJms(type, bytes);
            List<Infrastructure> scopes = infrastructures();
            List<Container> selected = new ArrayList<>();
            for (var scope : scopes) {
                Object cache = Reflect.readField(scope.processor(), "nonAnnotatedClasses");
                if (!(cache instanceof Set<?>)) throw new IllegalStateException("JMS negative scan cache is inaccessible");
                for (var c : containers(scope.registry())) {
                    Object listener = messageListener(c.instance());
                    Object bean = registrationBean(c.instance(), scope.processor());
                    if (AddedJmsListenerAdapter.ownerOf(bean) != type && (bean == null || !type.isInstance(bean))) continue;
                    relevant = true;
                    owners.get(type).registered = true;
                    requireAdapter(scope, c.instance(), listener, bean);
                    if (AddedJmsListenerAdapter.ownerOf(bean) != type)
                        throw new IllegalStateException("proxy or subclass JMS listener receiver for " + c.id());
                    // Validate every selected entry before stopping any of them.
                    requireLifecycle(c);
                    selected.add(c);
                }
            }
            boolean owning = relevant;
            return ListenerRetirement.of(!selected.isEmpty(), () -> commitBeanRefresh(type, scopes, selected, owning));
        } catch (Throwable failure) {
            // An optional unsupported JMS stack must not stop an unrelated
            // controller/service from following its ordinary reload path.
            if (!relevant) return ListenerRetirement.NONE;
            report(type, "bean recreation deferred: " + Failures.describe(failure));
            return ListenerRetirement.REFUSED;
        }
    }

    private synchronized boolean commitBeanRefresh(Class<?> type, List<Infrastructure> scopes, List<Container> selected,
                                                   boolean relevant) {
        try {
            stopAndRemove(selected);
            for (var scope : scopes) ((Set<?>) Reflect.readField(scope.processor(), "nonAnnotatedClasses")).remove(type);
            return true;
        } catch (Throwable failure) {
            if (!relevant) return true;
            report(type, "bean recreation deferred: " + Failures.describe(failure));
            return false;
        }
    }

    public synchronized boolean reloadJmsListeners(Class<?> type, Set<String> added, byte[] bytes) {
        var plan = AddedJmsListenerAdapter.inspect(bytes, added);
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
                    throw new IllegalStateException("only singleton JMS listener beans are supported");
                Supplier<Object> current = currentSingleton(factory, name, type);
                current.get();
                if (type.getSuperclass() != Object.class || type.getInterfaces().length != 0)
                    throw new IllegalStateException("listener inheritance/interfaces are unsupported");
                for (String id : plan.ids()) if (container(scope.registry(), id) != null)
                    throw new IllegalStateException("listener id already belongs to an existing container: " + id);
                for (var method : plan.methods()) requireFactory(scope, method);
                var delegate = AddedJmsListenerAdapter.create(type, current, plan);
                Class<?> annotation = Class.forName("org.springframework.jms.annotation.JmsListener", false, type.getClassLoader());
                Method process = Reflect.findMethod(scope.processor().getClass(), "processJmsListener", annotation, Method.class, Object.class);
                if (process == null) throw new IllegalStateException("JMS listener processor method is inaccessible");
                for (int i = 0; i < delegate.methods().size(); i++) {
                    Method method = delegate.methods().get(i);
                    Object metadata = Arrays.stream(method.getDeclaredAnnotations()).filter(a -> a.annotationType() == annotation).findFirst().orElseThrow();
                    try (var guard = NativeListenerSetup.beforeStart(scope.processor(), delegate.bean(),
                            (endpoint, candidate, adapter) -> requireAdapter(scope, candidate, adapter, delegate.bean()))) {
                        process.invoke(scope.processor(), metadata, method, delegate.bean());
                    } finally {
                        Object registered = container(scope.registry(), plan.ids().get(i));
                        if (registered != null && (NativeListenerSetup.produced(registered, scope.processor(), delegate.bean())
                                || listenerBean(messageListener(registered)) == delegate.bean())) {
                            created.add(new Container(scope.registry(), plan.ids().get(i), registered));
                            owners.get(type).registered = true;
                        }
                    }
                    Object registered = container(scope.registry(), plan.ids().get(i));
                    if (registered == null) throw new IllegalStateException("JMS processor did not register " + plan.ids().get(i));
                    requireAdapter(scope, registered, messageListener(registered), delegate.bean());
                    changed = true;
                }
            }
            if (!found) throw new IllegalStateException("no matching singleton with a JMS listener processor was found");
        } catch (Throwable failure) {
            try { stopAndRemove(created); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            report(type, Failures.describe(failure));
            return false;
        }
        if (changed) ReloadEffects.note("added @JmsListener registered");
        return changed;
    }

    private static boolean declaresJms(Class<?> type, byte[] bytes) {
        String prefix = "org.springframework.jms.annotation.";
        if (bytes == null) {
            for (var a : type.getDeclaredAnnotations()) if (a.annotationType().getName().startsWith(prefix)) return true;
            for (var m : type.getDeclaredMethods()) for (var a : m.getDeclaredAnnotations())
                if (a.annotationType().getName().startsWith(prefix)) return true;
            return false;
        }
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG);
        String descriptor = "Lorg/springframework/jms/annotation/";
        if (node.visibleAnnotations != null) for (var a : node.visibleAnnotations) if (a.desc.startsWith(descriptor)) return true;
        for (var m : node.methods) if (m.visibleAnnotations != null)
            for (var a : m.visibleAnnotations) if (a.desc.startsWith(descriptor)) return true;
        return false;
    }

    private static void requireFactory(Infrastructure scope, org.objectweb.asm.tree.MethodNode method) throws Exception {
        Object defaultName = Reflect.readField(scope.processor(), "containerFactoryBeanName");
        if (!(defaultName instanceof String name)) throw new IllegalStateException("JMS default container factory is inaccessible");
        for (var a : method.visibleAnnotations) if (a.desc.equals(AddedJmsListenerAdapter.JMS) && a.values != null)
            for (int i = 0; i < a.values.size(); i += 2)
                if (a.values.get(i).equals("containerFactory")) name = (String) a.values.get(i + 1);
        requireFactory(scope, name);
    }
    private static Object requireFactory(Infrastructure scope, String name) throws Exception {
        if (name.isEmpty()) {
            Object configured = Reflect.readField(scope.processor(), "containerFactoryBeanName");
            if (!(configured instanceof String)) throw new IllegalStateException("JMS default factory inaccessible");
            name = (String) configured;
        }
        Object factory = SpringBeans.getBean(scope.context(), name);
        if (factory == null || !factory.getClass().getName().equals("org.springframework.jms.config.DefaultJmsListenerContainerFactory"))
            throw new IllegalStateException("standard DefaultJmsListenerContainerFactory required: " + name);
        if (!scope.processor().getClass().getName().equals(PROCESSOR) && !NativeListenerSetup.observed(scope.processor(), factory))
            throw new IllegalStateException("custom JMS listener setup was not observed at construction");
        for (String field : List.of("transactionManager", "taskExecutor")) {
            var metadata = Reflect.findField(factory.getClass(), field);
            if (metadata == null || metadata.get(factory) != null)
                throw new IllegalStateException("JMS factory " + field + " is unsupported for added listeners");
        }
        return factory;
    }

    // Spring owns the registrar and scanner. Only endpoint creation is an application extension here.
    private static boolean nativeProcessor(Object processor) throws ClassNotFoundException {
        if (processor == null) return false;
        Class<?> type = processor.getClass();
        Class<?> base = Class.forName(PROCESSOR, false, type.getClassLoader());
        if (type == base) return true;
        if (!base.isAssignableFrom(type)) return false;
        boolean createsEndpoint = false;
        for (Class<?> current = type; current != base; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (Set.of("registrar", "containerFactoryBeanName", "nonAnnotatedClasses").contains(field.getName())) return false;
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()) continue;
                if (method.getName().equals("createMethodJmsListenerEndpoint") && method.getParameterCount() == 0) {
                    createsEndpoint = true;
                } else if (Reflect.findMethod(base, method.getName(), method.getParameterTypes()) != null) {
                    return false;
                }
            }
        }
        return createsEndpoint;
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
            if (names.length != 1) throw new IllegalStateException("multiple JMS listener processors are unsupported");
            Object processor = SpringBeans.getBean(context, names[0]);
            if (!nativeProcessor(processor))
                throw new IllegalStateException("custom JMS listener processors are unsupported");
            Object registrar = Reflect.readField(processor, "registrar");
            if (registrar == null) throw new IllegalStateException("JMS registrar is inaccessible");
            Object registry = registrar.getClass().getMethod("getEndpointRegistry").invoke(registrar);
            if (registry == null || !registry.getClass().getName().equals(REGISTRY))
                throw new IllegalStateException("standard JMS endpoint registry is required");
            if (seen.add(registry)) result.add(new Infrastructure(context, processor, registry));
        }
        return result;
    }
    private static Object container(Object registry, String id) throws Exception {
        return registry.getClass().getMethod("getListenerContainer", String.class).invoke(registry, id);
    }
    private static List<Container> containers(Object registry) throws Exception {
        Object ids = registry.getClass().getMethod("getListenerContainerIds").invoke(registry);
        if (!(ids instanceof Set<?> set)) throw new IllegalStateException("JMS container IDs are inaccessible");
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
    /**
     * Whether a JMS consumer in these contexts is received by one of these beans or by an
     * added delegate of one. A consumer whose receiver cannot be read counts as one.
     */
    static boolean listensFor(List<Object> contexts, List<Object> beans) throws Exception {
        for (var scope : infrastructures(contexts)) for (var c : containers(scope.registry())) {
            Object listener = messageListener(c.instance());
            Object receiver = listenerBean(listener);
            if (receiver == null) {
                // A listener this broker cannot read could call one of these
                // beans, so it counts as one that does.
                String name = listener == null ? null : listener.getClass().getName();
                if (name == null || !ADAPTER.equals(name)) return true;
                continue;
            }
            for (Object bean : beans)
                if (bean != null && (receiver == bean || AddedJmsListenerAdapter.ownerOf(receiver) == bean.getClass())) return true;
        }
        return false;
    }

    private static Object registrationBean(Object container, Object processor) throws Exception {
        Object original = NativeListenerSetup.originBean(container, processor);
        return original != null ? original : listenerBean(messageListener(container));
    }

    private static Object handlerMethod(Object adapter) throws Exception {
        if (adapter == null) return null;
        Class<?> base = adapter.getClass();
        while (base != null && !ADAPTER.equals(base.getName())) base = base.getSuperclass();
        if (base == null) return null;
        var field = Reflect.findField(base, "handlerMethod");
        return field == null ? null : field.get(adapter);
    }

    private static void requireAdapter(Infrastructure scope, Object container, Object adapter, Object receiver) throws Exception {
        Object handler = handlerMethod(adapter);
        if (handler == null || receiver == null || handler.getClass().getMethod("getBean").invoke(handler) != receiver)
            throw new IllegalStateException("custom JMS listener adapter has no matching native callback");
        if (adapter.getClass().getName().equals(ADAPTER)
                && (NativeListenerSetup.originBean(container, scope.processor()) == null
                || NativeListenerSetup.originalAdapter(container, adapter, scope.processor(), receiver))) return;
        Method method = (Method) handler.getClass().getMethod("getMethod").invoke(handler);
        for (var annotation : method.getDeclaredAnnotations()) {
            if (!annotation.annotationType().getName().equals("org.springframework.jms.annotation.JmsListener")) continue;
            String name = (String) annotation.annotationType().getMethod("containerFactory").invoke(annotation);
            if (NativeListenerSetup.installed(container, adapter, scope.processor(), receiver, requireFactory(scope, name)))
                return;
        }
        throw new IllegalStateException("custom JMS listener adapter does not match its native factory registration");
    }

    private static Object listenerBean(Object listener) throws Exception {
        Object handler = handlerMethod(listener);
        return handler == null ? null : handler.getClass().getMethod("getBean").invoke(handler);
    }
    private static Map<?,?> registryMap(Object registry) {
        Object value = Reflect.readField(registry, "listenerContainers");
        if (!(value instanceof Map<?,?> map)) throw new IllegalStateException("JMS registry map is inaccessible");
        return map;
    }
    private static void requireLifecycle(Container c) throws Exception {
        if (!c.instance().getClass().getName().equals("org.springframework.jms.listener.DefaultMessageListenerContainer"))
            throw new IllegalStateException("standard DefaultMessageListenerContainer required: " + c.id());
        registryMap(c.registry());
        for (String method : List.of("destroy", "isRunning", "isActive", "getActiveConsumerCount", "getScheduledConsumerCount"))
            c.instance().getClass().getMethod(method);
    }
    private void stopAndRemove(List<Container> selected) throws Exception {
        for (var c : selected) requireLifecycle(c);
        for (var c : selected) {
            FutureTask<Void> work = retirements.get(c.instance());
            if (work == null) {
                Object instance = c.instance();
                work = new FutureTask<>(() -> {
                    // destroy() disables delivery, waits for invokers and closes
                    // sessions/consumers/connection. stop() alone retains resources.
                    instance.getClass().getMethod("destroy").invoke(instance);
                    return null;
                });
                retirements.put(instance, work);
                var thread = new Thread(work, "reclazz-jms-shutdown");
                thread.setDaemon(true);
                thread.start();
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stopTimeoutMillis);
        for (var c : selected) {
            try {
                retirements.get(c.instance()).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new IllegalStateException("JMS consumer shutdown unfinished; registry ownership retained for retry", timeout);
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("JMS shutdown wait interrupted");
            if ((Boolean) c.instance().getClass().getMethod("isRunning").invoke(c.instance())
                    || (Boolean) c.instance().getClass().getMethod("isActive").invoke(c.instance())
                    || ((Number) c.instance().getClass().getMethod("getActiveConsumerCount").invoke(c.instance())).intValue() != 0
                    || ((Number) c.instance().getClass().getMethod("getScheduledConsumerCount").invoke(c.instance())).intValue() != 0)
                throw new IllegalStateException("JMS consumer resources remain active: " + c.id());
        }
        for (var c : selected) {
            Map<?,?> map = registryMap(c.registry());
            synchronized (map) {
                if (!map.remove(c.id(), c.instance()))
                    throw new IllegalStateException("JMS container ownership changed during shutdown: " + c.id());
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
                    throw new IllegalStateException("JMS callback requires current plain singleton " + name + "; missing/proxy/subclass receiver refused");
                return bean;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot read JMS singleton " + name, failure); }
        };
    }
    /** A direct property target is not a consumer lifecycle transaction. */
    static void requireNoPropertyConsumer(List<Object> contexts, Object context, String name) throws Exception {
        Object factory = SpringBeans.getBeanFactory(context);
        if (factory == null) return;
        Object bean = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, name);
        if (bean == null) return;
        boolean owned = declaresJms(bean.getClass(), null);
        if (!owned) for (var scope : infrastructures(contexts)) for (var container : containers(scope.registry())) {
            Object receiver = listenerBean(messageListener(container.instance()));
            if (receiver == bean || AddedJmsListenerAdapter.ownerOf(receiver) == bean.getClass()) owned = true;
        }
        if (owned) {
            RestartLedger.note(name, "direct JMS constructor-property refresh needs a coordinated helper or restart");
            throw new IllegalStateException("direct JMS property consumer refused before singleton destruction: " + name);
        }
    }

    /**
     * A bounded helper -> leaf JMS owner refresh. Read the native edges and every
     * callback before Spring's recursive destruction removes that information.
     * Null means no owned JMS dependent; unsupported affected shapes throw.
     */
    static DependencyRefresh prepareDependencyRefresh(List<Object> contexts, Object context, String root) throws Exception {
        try { return inspectDependencyRefresh(contexts, context, root); }
        catch (Exception failure) {
            RestartLedger.note(root, "JMS dependency refresh refused before singleton destruction: " + Failures.describe(failure));
            throw failure;
        }
    }

    private static DependencyRefresh inspectDependencyRefresh(List<Object> contexts, Object context, String root) throws Exception {
        Object factory = SpringBeans.getBeanFactory(context);
        if (factory == null) return null;
        List<Infrastructure> scopes = infrastructures(contexts);
        if (scopes.isEmpty()) return null;
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
                    if (bean != null && (receiver == bean || AddedJmsListenerAdapter.ownerOf(receiver) == bean.getClass()))
                        throw new IllegalStateException("nonlocal JMS dependent requires a restart");
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
                Object receiver = registrationBean(c.instance(), scope.processor());
                if (receiver != bean && AddedJmsListenerAdapter.ownerOf(receiver) != type) continue;
                requireAdapter(scope, c.instance(), adapter, receiver);
                requireLifecycle(c);
                if (!Boolean.TRUE.equals(c.instance().getClass().getMethod("isActive").invoke(c.instance()))
                        || !Boolean.TRUE.equals(c.instance().getClass().getMethod("isRunning").invoke(c.instance())))
                    throw new IllegalStateException("dependent JMS consumer is inactive or shutting down: " + c.id());
                Object handler = handlerMethod(adapter);
                Method method = (Method) handler.getClass().getMethod("getMethod").invoke(handler);
                Object metadata = Arrays.stream(method.getDeclaredAnnotations())
                        .filter(a -> a.annotationType().getName().equals("org.springframework.jms.annotation.JmsListener"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("literal JMS callback metadata required"));
                String id = (String) ((java.lang.annotation.Annotation) metadata).annotationType().getMethod("id").invoke(metadata);
                if (!id.equals(c.id()) || id.isBlank() || id.contains("${") || id.contains("#{"))
                    throw new IllegalStateException("dependent JMS callback requires a stable literal id");
                requireFactory(scope, (String) ((java.lang.annotation.Annotation) metadata).annotationType().getMethod("containerFactory").invoke(metadata));
                boolean added = receiver != bean;
                if (added && (!receiver.getClass().isHidden()
                        || !(Reflect.readField(receiver, com.onurkat.reclazz.bootstrap.InjectedNames.PREFIX + "target") instanceof Supplier<?> current)
                        || current.get() != bean))
                    throw new IllegalStateException("dependent JMS delegate does not own the current singleton");
                if (!added && method.getDeclaringClass() != type)
                    throw new IllegalStateException("inherited dependent JMS callback requires a restart");
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
                    throw new IllegalStateException("dependent JMS owner requires one plain local leaf constructor: " + name);
                owners.add(new DependencyOwner(name, bean, cache));
                selected.addAll(owned);
            }
        }
        if (owners.isEmpty()) return null;
        if (contexts.size() != 1 || contexts.get(0) != context || scopes.size() != 1
                || scopes.get(0).context() != context || owners.size() != dependents.length)
            throw new IllegalStateException("JMS dependency refresh requires one root context and only direct JMS leaf owners");
        Object rootBean = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, root);
        Object raw = factory.getClass().getMethod("getBeanDefinition", String.class).invoke(factory, root);
        if (rootBean == null || !plainRoot(raw, rootBean)
                || ((String[]) factory.getClass().getMethod("getDependenciesForBean", String.class).invoke(factory, root)).length != 0)
            throw new IllegalStateException("JMS dependency refresh requires a plain directly constructed helper");
        // Other brokers in the same context are independent of this transaction.
        // A helper or owner that also receives Kafka or Rabbit messages would need
        // those consumers retired too, which this transaction does not do.
        var involved = new ArrayList<Object>();
        involved.add(rootBean);
        for (var owner : owners) involved.add(owner.bean());
        if (SpringKafkaReloader.listensFor(contexts, involved) || SpringRabbitReloader.listensFor(contexts, involved))
            throw new IllegalStateException("a JMS dependency owner that also listens on another broker requires a restart");
        for (var scope : scopes) for (var c : containers(scope.registry())) {
            if (AddedJmsListenerAdapter.ownerOf(listenerBean(messageListener(c.instance()))) == rootBean.getClass())
                throw new IllegalStateException("a JMS consumer cannot also be the dependency root");
        }
        Object merged = factory.getClass().getMethod("getMergedBeanDefinition", String.class).invoke(factory, root);
        if (!(Reflect.readField(scopes.get(0).processor(), "nonAnnotatedClasses") instanceof Set<?>))
            throw new IllegalStateException("JMS negative scan cache is inaccessible");
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
            if (!name.equals("org.springframework.jms.annotation.JmsListener")
                    && !name.equals("org.springframework.messaging.handler.annotation.SendTo")
                    && !name.equals("java.lang.Deprecated")) return false;
        }
        return true;
    }

    private static boolean plainRoot(Object definition, Object bean) throws Exception {
        Class<?> type = bean.getClass();
        if (type.getSuperclass() != Object.class || type.getInterfaces().length != 0
                || type.getDeclaredConstructors().length != 1 || !plainCallbacks(type) || declaresJms(type, null)) return false;
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
        private final SpringJmsReloader coordinator = new SpringJmsReloader(null);

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
                throw new IllegalStateException("JMS dependency ownership changed after preflight");
            for (int i = 0; i < owners.size(); i++) {
                var a = owners.get(i); var b = fresh.owners.get(i);
                if (!a.name().equals(b.name()) || a.bean() != b.bean() || a.cache().merged() != b.cache().merged())
                    throw new IllegalStateException("JMS dependent singleton changed after preflight");
            }
            // Timeout leaves helper/owner singletons intact. An inactive retirement
            // is refused on later saves, not started concurrently a second time.
            try { coordinator.stopAndRemove(selected); }
            catch (Exception failure) {
                RestartLedger.note(root, "dependent JMS shutdown incomplete; helper and owner singletons retained; restart required");
                throw failure;
            }
            try {
                for (var owner : owners) owner.cache().apply();
                for (var callback : callbacks)
                    ((Set<?>) Reflect.readField(callback.scope().processor(), "nonAnnotatedClasses")).remove(callback.type());
                Object[] result = SpringBeanReloader.destroyAndRefreshBean(context, root);
                for (var owner : owners) context.getClass().getMethod("getBean", String.class).invoke(context, owner.name());
                for (var callback : callbacks) if (callback.added()) {
                    Method process = Reflect.findMethod(callback.scope().processor().getClass(), "processJmsListener",
                            Class.forName("org.springframework.jms.annotation.JmsListener", false, callback.type().getClassLoader()),
                            Method.class, Object.class);
                    process.invoke(callback.scope().processor(), callback.metadata(), callback.method(), callback.receiver());
                }
                for (var callback : callbacks) {
                    Object container = container(callback.scope().registry(), callback.container().id());
                    Object current = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, callback.name());
                    if (container == null || container == callback.container().instance()
                            || !Boolean.TRUE.equals(container.getClass().getMethod("isRunning").invoke(container))
                            || !Boolean.TRUE.equals(container.getClass().getMethod("isActive").invoke(container)) || current == null
                            || current.getClass() != callback.type()
                            || listenerBean(messageListener(container)) != (callback.added() ? callback.receiver() : current))
                        throw new IllegalStateException("JMS dependent callback was not restored: " + callback.container().id());
                    requireAdapter(callback.scope(), container, messageListener(container), callback.added() ? callback.receiver() : current);
                }
                ReloadEffects.note("dependent JMS consumers re-created");
                return result;
            } catch (Exception | LinkageError failure) {
                // No rollback of application work. Retire only this attempt's known
                // callback IDs; unrelated registry entries are never cleared.
                List<Container> created = new ArrayList<>();
                for (var callback : callbacks) {
                    Object value = container(callback.scope().registry(), callback.container().id());
                    if (value != null && value != callback.container().instance()
                            && AddedJmsListenerAdapter.ownerOf(registrationBean(value, callback.scope().processor())) == callback.type())
                        created.add(new Container(callback.scope().registry(), callback.container().id(), value));
                }
                try { coordinator.stopAndRemove(created); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                RestartLedger.note(root, "dependent JMS refresh partially applied; restart required");
                throw failure;
            }
        }
    }

    private static void report(Class<?> type, String reason) {
        StatusReporter.warn("@JmsListener reload for " + type.getName() + " could not apply: " + reason);
        RestartLedger.note(type.getName(), "JMS listener declaration not applied: " + reason);
    }
}
