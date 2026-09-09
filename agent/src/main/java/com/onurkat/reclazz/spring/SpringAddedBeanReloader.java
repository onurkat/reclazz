/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.Failures;

import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Registers only added factory methods, without rerunning configuration parsing. */
public final class SpringAddedBeanReloader {
    private final PlatformContext platform;
    // Values must not retain the factory key through a supplier/bean/context.
    private final Map<Object, Registry> owned = new WeakHashMap<>();
    private record Owned(WeakReference<Class<?>> owner, WeakReference<Object> definition,
                         AtomicReference<WeakReference<Object>> instance) { }
    private static final class Registry {
        final Map<String, Owned> definitions = new ConcurrentHashMap<>();
        boolean observing;
    }

    public SpringAddedBeanReloader(PlatformContext platform) { this.platform = platform; }

    public synchronized boolean reloadBeanMethods(Class<?> type, Set<String> added, byte[] bytes) {
        if (bytes == null) return true;
        var plan = AddedBeanAdapter.inspect(bytes, added);
        for (String reason : plan.refused()) refuse(type, reason);
        boolean success = plan.refused().isEmpty();
        boolean foundConfiguration = false;
        for (Object context : platform.getAllApplicationContexts()) {
            Object factory = SpringBeans.getBeanFactory(context);
            if (factory == null) continue;
            try {
                Registry registry = owned.computeIfAbsent(factory, ignored -> new Registry());
                Map<String, Owned> registrations = registry.definitions;
                // Removal/annotation removal still reaches here with an empty plan.
                for (var entry : List.copyOf(registrations.entrySet())) {
                    if (entry.getValue().owner().get() != type) continue;
                    if (stillOwned(factory, entry.getKey(), entry.getValue())) {
                        call(factory, "removeBeanDefinition", entry.getKey());
                        ReloadEffects.note("added bean " + entry.getKey() + " removed");
                    }
                    registrations.remove(entry.getKey());
                }
                if (plan.factories().isEmpty()) continue;
                String[] configs = (String[]) factory.getClass().getMethod("getBeanNamesForType",
                        Class.class, boolean.class, boolean.class).invoke(factory, type, true, false);
                if (configs.length == 0) continue;
                foundConfiguration = true;
                if (configs.length != 1) throw new IllegalStateException("requires exactly one local configuration bean");
                String configName = configs[0];
                if (!Boolean.TRUE.equals(call(factory, "isSingleton", configName)))
                    throw new IllegalStateException("configuration must be a singleton");
                Object config = call(factory, "getBean", configName);
                if (config.getClass() != type) throw new IllegalStateException("proxied configuration is not supported");
                observeCreations(factory, registry);
                List<AddedBeanAdapter.Factory> prepared = new ArrayList<>();
                for (var method : plan.factories()) {
                    try {
                        registerDefinition(type, factory, configName, method, registrations);
                        prepared.add(method);
                    } catch (Throwable failure) {
                        success = false;
                        refuse(type, method.method().name + method.method().desc + ": " + Failures.describe(failure));
                    }
                }
                // An added product can inject another product from this save,
                // even when its factory appears first in the source file.
                for (var method : prepared) {
                    try {
                        initialize(factory, method.name(), registrations);
                    } catch (Throwable failure) {
                        success = false;
                        refuse(type, method.method().name + method.method().desc + ": " + Failures.describe(failure));
                    }
                }
            } catch (Throwable failure) {
                success = false;
                refuse(type, Failures.describe(failure));
            }
        }
        if (!plan.factories().isEmpty() && !foundConfiguration) {
            refuse(type, "no local configuration bean found");
            success = false;
        }
        return success;
    }

    private void registerDefinition(Class<?> type, Object factory, String configName,
                          AddedBeanAdapter.Factory method, Map<String, Owned> registrations) throws Throwable {
        String name = method.name();
        if (Boolean.TRUE.equals(call(factory, "containsBean", name))
                || Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))
                || Boolean.TRUE.equals(call(factory, "isAlias", name)))
            throw new IllegalStateException("bean name '" + name + "' is already in use");
        Class<?> resultType = MethodType.fromMethodDescriptorString(method.method().desc, type.getClassLoader()).returnType();
        ClassLoader spring = factory.getClass().getClassLoader();
        rejectInfrastructure(resultType, spring);
        Supplier<?> delegate = AddedBeanAdapter.create(type, () -> {
            try {
                Object current = call(factory, "getBean", configName);
                if (current.getClass() != type || !Boolean.TRUE.equals(call(factory, "isSingleton", configName)))
                    throw new IllegalStateException("configuration is no longer an unproxied singleton");
                return current;
            } catch (Exception failure) { throw new IllegalStateException("configuration cannot be resolved", failure); }
        }, method, metadata -> AddedBeanArguments.prepare(factory, name, metadata));
        Supplier<?> checked = () -> {
            Object product = Objects.requireNonNull(delegate.get(), "added @Bean factory returned null");
            try { rejectInfrastructure(product.getClass(), spring); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return product;
        };
        Class<?> definitionType = Class.forName("org.springframework.beans.factory.support.RootBeanDefinition", true, spring);
        Class<?> definitionInterface = Class.forName("org.springframework.beans.factory.config.BeanDefinition", false, spring);
        Object definition = definitionType.getConstructor(Class.class).newInstance(resultType);
        definitionType.getMethod("setInstanceSupplier", Supplier.class).invoke(definition, checked);
        call(definition, "setInitMethodName", method.init());
        call(definition, "setDestroyMethodName", method.destroy());
        factory.getClass().getMethod("registerBeanDefinition", String.class, definitionInterface).invoke(factory, name, definition);
        Owned registration = new Owned(new WeakReference<>(type), new WeakReference<>(definition),
                new AtomicReference<>(new WeakReference<>(null)));
        registrations.put(name, registration);
    }

    private static void initialize(Object factory, String name, Map<String, Owned> registrations) throws Throwable {
        Owned registration = registrations.get(name);
        if (registration == null) throw new IllegalStateException("added definition was removed before initialization: " + name);
        try {
            if (!stillOwned(factory, name, registration))
                throw new IllegalStateException("added registration was replaced before initialization: " + name);
            Object product = call(factory, "getBean", name);
            registration.instance().set(new WeakReference<>(product));
            ReloadEffects.note("added bean " + name + " registered");
            StatusReporter.detail("Added @Bean registered: " + name);
        } catch (Throwable failure) {
            // Only our definition is removed; callbacks and dependent destruction
            // are live application work and cannot be rolled back here.
            if (stillOwned(factory, name, registration)) call(factory, "removeBeanDefinition", name);
            registrations.remove(name, registration);
            throw failure;
        }
    }

    private static boolean stillOwned(Object factory, String name, Owned registration) throws Exception {
        if (!Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))
                || call(factory, "getBeanDefinition", name) != registration.definition().get()) return false;
        Object singleton = call(factory, "getSingleton", name);
        // A vanished singleton may have been destroyed during configuration
        // refresh. An externally installed replacement is somebody else's bean.
        return singleton == null || singleton == registration.instance().get().get();
    }

    private static void observeCreations(Object factory, Registry registry) throws Exception {
        if (registry.observing) return;
        Class<?> processor = Class.forName("org.springframework.beans.factory.config.BeanPostProcessor",
                false, factory.getClass().getClassLoader());
        WeakReference<Object> factoryRef = new WeakReference<>(factory);
        Object observer = Proxy.newProxyInstance(processor.getClassLoader(), new Class<?>[]{processor}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Reclazz added bean ownership observer";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            if (method.getName().equals("postProcessAfterInitialization")) {
                Owned registration = registry.definitions.get((String) args[1]);
                Object currentFactory = factoryRef.get();
                if (registration != null && currentFactory != null) {
                    try {
                        if (call(currentFactory, "getBeanDefinition", (String) args[1]) == registration.definition().get())
                            registration.instance().set(new WeakReference<>(args[0]));
                    } catch (Exception unavailable) {
                        // Fail closed on the next ownership check, without
                        // breaking an application's otherwise successful creation.
                        registration.instance().set(new WeakReference<>(null));
                    }
                }
            }
            // Registered after the application's processors, so this observes
            // their finished object, including a proxy. It changes no bean.
            // No reloader lock: creation can hold Spring's singleton lock on
            // another thread while a reload is waiting to enter the factory.
            return args[0];
        });
        factory.getClass().getMethod("addBeanPostProcessor", processor).invoke(factory, observer);
        registry.observing = true;
    }

    private static void rejectInfrastructure(Class<?> type, ClassLoader spring) throws ClassNotFoundException {
        for (String name : List.of("org.springframework.beans.factory.FactoryBean",
                "org.springframework.beans.factory.config.BeanPostProcessor",
                "org.springframework.beans.factory.config.BeanFactoryPostProcessor"))
            if (Class.forName(name, false, spring).isAssignableFrom(type))
                throw new IllegalArgumentException("infrastructure factory products are not supported: " + type.getName());
    }

    private static Object call(Object target, String method, String name) throws Exception {
        return target.getClass().getMethod(method, String.class).invoke(target, name);
    }

    private static void refuse(Class<?> type, String reason) {
        String message = "Added @Bean on " + type.getName() + " needs a restart: " + reason;
        StatusReporter.warn(message);
        RestartLedger.note(type.getName(), message);
    }
}
