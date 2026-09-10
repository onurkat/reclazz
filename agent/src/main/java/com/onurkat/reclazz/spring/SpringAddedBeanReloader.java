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
import com.onurkat.reclazz.util.Reflect;

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
                         AtomicReference<WeakReference<Object>> instance, List<String> aliases) { }
    private static final class Registry {
        final Map<String, Owned> definitions = new ConcurrentHashMap<>();
        boolean observing;
    }

    public SpringAddedBeanReloader(PlatformContext platform) { this.platform = platform; }

    public synchronized boolean reloadBeanMethods(Class<?> type, Set<String> added, byte[] bytes) {
        if (bytes == null) return true;
        var plan = AddedBeanAdapter.inspect(bytes, added);
        SpringConfigurationCalls.publish(type, plan);
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
                    if (removeOwned(factory, entry.getKey(), entry.getValue())) {
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
                if (!SpringConfigurationCalls.matches(config, type, factory, plan.full()))
                    throw new IllegalStateException("requires the native singleton configuration without additional proxies");
                observeCreations(factory, registry);
                List<AddedBeanAdapter.Factory> prepared = new ArrayList<>();
                for (var method : plan.factories()) {
                    try {
                        registerDefinition(type, factory, configName, method, registrations, plan.full());
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
                          AddedBeanAdapter.Factory method, Map<String, Owned> registrations, boolean full) throws Throwable {
        String name = method.name();
        for (String candidate : method.names()) {
            if (Boolean.TRUE.equals(call(factory, "containsBean", candidate))
                    || Boolean.TRUE.equals(call(factory, "containsBeanDefinition", candidate))
                    || Boolean.TRUE.equals(call(factory, "isAlias", candidate)))
                throw new IllegalStateException("bean name or alias '" + candidate + "' is already in use");
        }
        // Refuse before registration if later alias ownership cannot be checked.
        if (!method.aliases().isEmpty()) aliasBindings(factory);
        Class<?> resultType = MethodType.fromMethodDescriptorString(method.method().desc, type.getClassLoader()).returnType();
        ClassLoader spring = factory.getClass().getClassLoader();
        rejectInfrastructure(resultType, spring);
        Supplier<?> delegate = AddedBeanAdapter.create(type, () -> {
            try {
                Object current = call(factory, "getBean", configName);
                if (!SpringConfigurationCalls.matches(current, type, factory, full)
                        || !Boolean.TRUE.equals(call(factory, "isSingleton", configName)))
                    throw new IllegalStateException("configuration is no longer a supported singleton");
                return current;
            } catch (Exception failure) { throw new IllegalStateException("configuration cannot be resolved", failure); }
        }, method, (metadata, descriptor) -> AddedBeanArguments.prepare(factory, name, metadata, descriptor));
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
        definitionType.getMethod("setPrimary", boolean.class).invoke(definition, method.primary());
        if (method.qualifier() != null) {
            // Selection metadata belongs on the definition: the original class
            // cannot expose an added factory method through reflection.
            Class<?> qualifierType = Class.forName("org.springframework.beans.factory.support.AutowireCandidateQualifier", true, spring);
            Object qualifier = qualifierType.getConstructor(String.class, Object.class).newInstance(
                    "org.springframework.beans.factory.annotation.Qualifier", method.qualifier());
            definitionType.getMethod("addQualifier", qualifierType).invoke(definition, qualifier);
        }
        factory.getClass().getMethod("registerBeanDefinition", String.class, definitionInterface).invoke(factory, name, definition);
        Owned registration = new Owned(new WeakReference<>(type), new WeakReference<>(definition),
                new AtomicReference<>(new WeakReference<>(null)), method.aliases());
        registrations.put(name, registration);
        try {
            for (String alias : method.aliases())
                factory.getClass().getMethod("registerAlias", String.class, String.class).invoke(factory, name, alias);
        } catch (Throwable failure) {
            discardFailed(factory, name, registration, registrations, failure);
            throw failure;
        }
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
            discardFailed(factory, name, registration, registrations, failure);
            throw failure;
        }
    }

    private static void discardFailed(Object factory, String name, Owned registration,
                                      Map<String, Owned> registrations, Throwable failure) {
        try {
            removeOwned(factory, name, registration);
            registrations.remove(name, registration);
        } catch (Throwable cleanup) {
            // Keep the registration and report cleanup without hiding the original failure.
            failure.addSuppressed(cleanup);
        }
    }

    private static boolean removeOwned(Object factory, String name, Owned registration) throws Exception {
        if (!stillOwned(factory, name, registration)) return false;
        Map<?, ?> bindings = registration.aliases().isEmpty() ? Map.of() : aliasBindings(factory);
        call(factory, "removeBeanDefinition", name);
        // Destruction callbacks may have installed a new owner of this name.
        if (Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))
                || call(factory, "getSingleton", name) != null) return true;
        // Never hold the alias lock while destroying beans/application callbacks.
        synchronized (bindings) {
            for (String alias : registration.aliases()) {
                // Comparing the canonical root would wrongly remove an alias
                // externally retargeted through a different intermediate alias.
                if (name.equals(bindings.get(alias))) call(factory, "removeAlias", alias);
            }
        }
        return true;
    }

    private static Map<?, ?> aliasBindings(Object factory) {
        Object bindings = Reflect.readField(factory, "aliasMap");
        if (!(bindings instanceof Map<?, ?> map))
            throw new IllegalStateException("cannot inspect direct alias bindings for ownership");
        return map;
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
