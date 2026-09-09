/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Adds lifecycle callbacks through Spring's own creation/destruction SPI. */
public final class SpringLifecycleReloader {
    private static final String BPP = "org.springframework.beans.factory.config.BeanPostProcessor";
    private static final String DESTRUCTION = "org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor";
    private static final String COMMON = "org.springframework.context.annotation.CommonAnnotationBeanPostProcessor";
    private final PlatformContext platform;

    public SpringLifecycleReloader(PlatformContext platform) { this.platform = platform; }

    private record Scope(Object factory, List<String> names, Processor processor,
                         AddedLifecycleAdapter.Callbacks callbacks) { }

    /** Preflight every context before any processor or singleton is changed. */
    Prepared prepare(Class<?> type, Set<String> added, byte[] bytes) {
        if (bytes == null) return new Prepared(type, List.of());
        var plan = AddedLifecycleAdapter.inspect(bytes, added);
        try {
            if (!plan.refused().isEmpty()) throw new IllegalArgumentException(String.join("; ", plan.refused()));
            List<Scope> scopes = new ArrayList<>();
            AddedLifecycleAdapter.Callbacks callbacks = null;
            for (Object context : platform.getAllApplicationContexts()) {
                Object factory = SpringBeans.getBeanFactory(context);
                if (factory == null) continue;
                List<?> processors = (List<?>) factory.getClass().getMethod("getBeanPostProcessors").invoke(factory);
                Processor processor = findProcessor(processors);
                boolean owned = processor != null && processor.plans.values().stream().anyMatch(p -> p.owner() == type);
                if (plan.empty() && !owned) continue;
                String[] names = (String[]) factory.getClass().getMethod("getBeanNamesForType",
                        Class.class, boolean.class, boolean.class).invoke(factory, type, true, false);
                List<String> local = new ArrayList<>();
                for (String name : names) {
                    if (!Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))) continue;
                    if (!Boolean.TRUE.equals(call(factory, "isSingleton", name)))
                        throw new IllegalArgumentException(name + ": requires a singleton");
                    Object bean = call(factory, "getSingleton", name);
                    if (bean == null || bean.getClass() != type)
                        throw new IllegalArgumentException(name + ": requires an already initialized plain bean");
                    Object definition = call(factory, "getBeanDefinition", name);
                    if (definition.getClass().getMethod("getFactoryMethodName").invoke(definition) != null)
                        throw new IllegalArgumentException(name + ": factory method products are unsupported");
                    for (String getter : List.of("getInitMethodName", "getDestroyMethodName")) {
                        Object configured = definition.getClass().getMethod(getter).invoke(definition);
                        if (configured != null && ((plan.init() != null && configured.equals(plan.init().name))
                                || (plan.destroy() != null && configured.equals(plan.destroy().name))))
                            throw new IllegalArgumentException(name + ": callback is also a configured lifecycle method");
                    }
                    local.add(name);
                }
                if (local.isEmpty()) {
                    if (owned) scopes.add(new Scope(factory, List.of(), processor, null));
                    continue;
                }
                if (!plan.empty()) {
                    if (type.getSuperclass() != Object.class) throw new IllegalArgumentException("inherited owner is unsupported");
                    requireProcessor(processors, factory.getClass().getClassLoader());
                    for (String spi : List.of(BPP, "org.springframework.beans.factory.FactoryBean",
                            "org.springframework.beans.factory.config.BeanFactoryPostProcessor")) {
                        if (Class.forName(spi, false, factory.getClass().getClassLoader()).isAssignableFrom(type))
                            throw new IllegalArgumentException("infrastructure beans are unsupported");
                    }
                    if (callbacks == null) callbacks = AddedLifecycleAdapter.capture(type, plan);
                }
                scopes.add(new Scope(factory, local, processor, callbacks));
            }
            if (!plan.empty() && scopes.isEmpty()) throw new IllegalArgumentException("no managed local singleton found");
            return new Prepared(type, scopes);
        } catch (Throwable failure) {
            refuse(type, Failures.describe(failure));
            return null;
        }
    }

    private static void requireProcessor(List<?> processors, ClassLoader loader) throws Exception {
        Class<?> lifecycle = Class.forName("org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor",
                false, loader);
        int standard = 0;
        for (Object candidate : processors) {
            if (!lifecycle.isInstance(candidate)) continue;
            if (!candidate.getClass().getName().equals(COMMON))
                throw new IllegalArgumentException("custom lifecycle processors are unsupported");
            if (!(Reflect.readField(candidate, "initAnnotationType") instanceof Class<?> init)
                    || !init.getName().equals("javax.annotation.PostConstruct")
                    || !(Reflect.readField(candidate, "destroyAnnotationType") instanceof Class<?> destroy)
                    || !destroy.getName().equals("javax.annotation.PreDestroy"))
                throw new IllegalArgumentException("requires the standard javax lifecycle annotations");
            standard++;
        }
        if (standard != 1) throw new IllegalArgumentException("requires one standard CommonAnnotationBeanPostProcessor");
    }

    final class Prepared {
        private final Class<?> owner;
        private final List<Scope> scopes;
        Prepared(Class<?> owner, List<Scope> scopes) { this.owner = owner; this.scopes = scopes; }

        boolean install() {
            try {
                for (Scope scope : scopes) {
                    Processor processor = scope.processor();
                    if (processor == null) {
                        processor = new Processor();
                        ClassLoader loader = scope.factory().getClass().getClassLoader();
                        Class<?> spi = Class.forName(DESTRUCTION, false, loader);
                        Object observer = Proxy.newProxyInstance(spi.getClassLoader(), new Class<?>[]{spi}, processor);
                        scope.factory().getClass().getMethod("addBeanPostProcessor", Class.forName(BPP, false, loader))
                                .invoke(scope.factory(), observer);
                    }
                    processor.plans.entrySet().removeIf(e -> e.getValue().owner() == owner);
                    if (scope.callbacks() != null)
                        for (String name : scope.names()) processor.plans.put(name, scope.callbacks());
                }
                return true;
            } catch (Throwable failure) {
                refuse(owner, Failures.describe(failure));
                return false;
            }
        }
    }

    private static Processor findProcessor(List<?> processors) {
        for (Object candidate : processors)
            if (Proxy.isProxyClass(candidate.getClass()) && Proxy.getInvocationHandler(candidate) instanceof Processor p) return p;
        return null;
    }

    // Owned by the factory's processor list, never by an agent-global map.
    // Unbound handles do not retain a failed or externally discarded instance.
    static final class Processor implements InvocationHandler {
        final Map<String, AddedLifecycleAdapter.Callbacks> plans = new ConcurrentHashMap<>();
        final Map<Identity, AddedLifecycleAdapter.Callbacks> instances = new ConcurrentHashMap<>();
        final ReferenceQueue<Object> collected = new ReferenceQueue<>();

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Reclazz added lifecycle processor";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            for (Object stale; (stale = collected.poll()) != null;) instances.remove(stale);
            Object bean = args[0];
            Identity key = new Identity(bean, collected);
            switch (method.getName()) {
                case "postProcessBeforeInitialization" -> {
                    var callbacks = plans.get((String) args[1]);
                    if (callbacks == null) return bean;
                    if (bean.getClass() != callbacks.owner())
                        throw new IllegalStateException("Added lifecycle callback requires a plain " + callbacks.owner().getName());
                    if (instances.putIfAbsent(key, callbacks) == null) {
                        try {
                            if (callbacks.init() != null) callbacks.init().invokeExact(bean);
                        } catch (Throwable failure) {
                            instances.remove(key);
                            throw new IllegalStateException("Added init callback failed for " + args[1], failure);
                        }
                    }
                    return bean;
                }
                case "postProcessAfterInitialization" -> { return bean; }
                case "requiresDestruction" -> {
                    var callbacks = instances.get(key);
                    return callbacks != null && callbacks.destroy() != null;
                }
                case "postProcessBeforeDestruction" -> {
                    var callbacks = instances.remove(key);
                    if (callbacks != null && callbacks.destroy() != null) {
                        try { callbacks.destroy().invokeExact(bean); }
                        catch (Throwable failure) {
                            // Like Spring's annotation processor: report, then
                            // allow DisposableBean and other beans to finish.
                            refuse(callbacks.owner(), "destroy callback failed for " + args[1] + ": " + Failures.describe(failure));
                        }
                    }
                    return null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            }
        }
    }

    private static final class Identity extends WeakReference<Object> {
        private final int hash;
        Identity(Object bean, ReferenceQueue<Object> queue) { super(bean, queue); hash = System.identityHashCode(bean); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || (other instanceof Identity key && get() != null && get() == key.get());
        }
    }

    private static Object call(Object target, String method, String name) throws Exception {
        return target.getClass().getMethod(method, String.class).invoke(target, name);
    }

    private static void refuse(Class<?> owner, String reason) {
        String message = "Added lifecycle methods on " + owner.getName() + " need a restart: " + reason;
        StatusReporter.warn(message);
        RestartLedger.note(owner.getName(), message);
    }
}
