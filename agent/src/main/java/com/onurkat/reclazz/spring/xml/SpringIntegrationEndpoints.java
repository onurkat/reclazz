/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;

/**
 * Swaps an edited Spring Integration XML endpoint (service-activator, transformer,
 * filter, router and the other {@code ConsumerEndpointFactoryBean} shapes) in a
 * running context.
 *
 * <p>The namespace parser registers a named endpoint whose {@code handler} property
 * refers to a generated handler factory bean ({@code ServiceActivatorFactoryBean#0}),
 * plus an {@code <id>.handler} alias and a few context-wide infrastructure beans.
 * Spring keeps no way to redefine an endpoint, so the old one is stopped (which
 * unsubscribes it from its channel), both definitions are replaced under their
 * live names, and the new endpoint is created and started. If anything fails the
 * old definitions are put back and the old endpoint is started again.
 *
 * <p>The parsed factory is then aligned with the live names, so the generic
 * classifier sees the swapped endpoints and the shared infrastructure as
 * unchanged and reports only what is left. Endpoints that were added or removed,
 * channels, pollers and gateways stay on the generic path, which asks for a
 * restart.
 */
final class SpringIntegrationEndpoints {
    private SpringIntegrationEndpoints() { }

    static final String ENDPOINT = "org.springframework.integration.config.ConsumerEndpointFactoryBean";
    private static final String CONFIG = "org.springframework.integration.config.";

    /** Result for one owner context: endpoints swapped and aliases the parser derived. */
    record Outcome(int swapped, Set<String> derivedAliases, List<BeanDefinitionDiff.UnsafeChange> failures) {
        static final Outcome NONE = new Outcome(0, Set.of(), List.of());
    }

    static Outcome apply(Object context, Object live, Object parsed, Path xmlPath, String label) {
        if (!usesIntegration(parsed)) return Outcome.NONE;
        Outcome polling = SpringIntegrationPollingAdapters.apply(context, live, parsed, xmlPath, label);
        int swapped = polling.swapped();
        Set<String> derived = new HashSet<>();
        List<BeanDefinitionDiff.UnsafeChange> failures = new ArrayList<>(polling.failures());
        for (String name : SpringReflection.getBeanDefinitionNames(parsed)) {
            Object next = SpringReflection.getBeanDefinition(parsed, name);
            if (next == null || !ENDPOINT.equals(SpringReflection.getBeanClassName(next))) continue;
            // An endpoint without an id is named ConsumerEndpointFactoryBean#N, and N depends on
            // how many endpoints were parsed before it: a fresh parse of one file and the live boot
            // number them differently, so the same name can be another file's endpoint. Only an
            // explicit id identifies the same endpoint; generated ones stay on the generic path.
            if (generated(name)) continue;
            Object previous = SpringReflection.getBeanDefinition(live, name);
            String nextHandler = handlerName(next);
            if (nextHandler != null) derived.add(name + ".handler");
            if (previous == null || !ENDPOINT.equals(SpringReflection.getBeanClassName(previous))) continue;
            String liveHandler = handlerName(previous);
            if (nextHandler == null || liveHandler == null) continue;
            try {
                Object nextHandlerDefinition = SpringReflection.getBeanDefinition(parsed, nextHandler);
                Object liveHandlerDefinition = SpringReflection.getBeanDefinition(live, liveHandler);
                if (nextHandlerDefinition == null || liveHandlerDefinition == null) continue;
                Object aligned = withHandler(next, liveHandler);
                if (!(same(previous, aligned) && same(liveHandlerDefinition, nextHandlerDefinition))) {
                    swap(context, live, name, liveHandler, previous, liveHandlerDefinition,
                            aligned, copy(nextHandlerDefinition));
                    swapped++;
                    StatusReporter.info("Spring Integration endpoint " + name + label
                            + " replaced: old endpoint stopped, new handler subscribed");
                }
                align(parsed, name, nextHandler, liveHandler, aligned, nextHandlerDefinition);
            } catch (Exception failure) {
                failures.add(new BeanDefinitionDiff.UnsafeChange(name,
                        "Spring Integration endpoint swap failed, old endpoint kept: " + SpringReflection.rootCause(failure)));
                SpringReflection.removeBeanDefinition(parsed, name);
                SpringReflection.removeBeanDefinition(parsed, nextHandler);
            }
        }
        dropSharedInfrastructure(live, parsed, xmlPath);
        return new Outcome(swapped, derived, failures);
    }

    static boolean usesIntegration(Object factory) {
        for (String name : SpringReflection.getBeanDefinitionNames(factory)) {
            Object definition = SpringReflection.getBeanDefinition(factory, name);
            String type = definition == null ? null : SpringReflection.getBeanClassName(definition);
            if (type != null && type.startsWith("org.springframework.integration.")) return true;
        }
        return false;
    }

    private static void swap(Object context, Object live, String name, String handler,
                             Object oldEndpoint, Object oldHandler, Object newEndpoint, Object newHandler) throws Exception {
        stop(live, name);
        SpringReflection.removeBeanDefinition(live, name);
        SpringReflection.removeBeanDefinition(live, handler);
        try {
            SpringReflection.registerBeanDefinition(live, handler, newHandler);
            SpringReflection.registerBeanDefinition(live, name, newEndpoint);
            start(context, live, name);
        } catch (Exception failure) {
            stop(live, name);
            SpringReflection.removeBeanDefinition(live, name);
            SpringReflection.removeBeanDefinition(live, handler);
            try {
                SpringReflection.registerBeanDefinition(live, handler, oldHandler);
                SpringReflection.registerBeanDefinition(live, name, oldEndpoint);
                start(context, live, name);
            } catch (Exception restore) {
                failure.addSuppressed(restore);
            }
            throw failure;
        }
    }

    private static void stop(Object live, String name) {
        Object endpoint = SpringReflection.getExistingSingleton(live, name);
        if (endpoint == null) return;
        try {
            if (Boolean.TRUE.equals(call(endpoint, "isRunning"))) call(endpoint, "stop");
        } catch (ReflectiveOperationException notLifecycle) { /* nothing subscribed */ }
    }

    private static void start(Object context, Object live, String name) throws Exception {
        Object endpoint = call(live, "getBean", name);
        boolean running;
        try { running = Boolean.TRUE.equals(call(context, "isRunning")); }
        catch (ReflectiveOperationException unknown) { running = true; }
        boolean autoStartup;
        try { autoStartup = !Boolean.FALSE.equals(call(endpoint, "isAutoStartup")); }
        catch (ReflectiveOperationException unknown) { autoStartup = true; }
        if (running && autoStartup && !Boolean.TRUE.equals(call(endpoint, "isRunning"))) call(endpoint, "start");
    }

    /** The parsed endpoint and handler under the live names, so the classifier sees them as unchanged. */
    private static void align(Object parsed, String name, String parsedHandler, String liveHandler,
                              Object endpoint, Object handler) throws Exception {
        if (parsedHandler.equals(liveHandler)) {
            SpringReflection.registerBeanDefinition(parsed, name, endpoint);
            return;
        }
        if (SpringReflection.containsBeanDefinition(parsed, liveHandler))
            throw new IllegalStateException("generated handler name " + liveHandler + " is taken in the edited file");
        String[] aliases = (String[]) call(parsed, "getAliases", parsedHandler);
        for (String alias : aliases) call(parsed, "removeAlias", alias);
        SpringReflection.removeBeanDefinition(parsed, parsedHandler);
        SpringReflection.registerBeanDefinition(parsed, liveHandler, handler);
        for (String alias : aliases) call(parsed, "registerAlias", liveHandler, alias);
        SpringReflection.registerBeanDefinition(parsed, name, endpoint);
    }

    /**
     * Namespace parsing registers context-wide SI infrastructure in every file that
     * uses the namespace. A bean that already exists live with the same class and
     * was not declared by this file is shared, not this file's change.
     */
    private static void dropSharedInfrastructure(Object live, Object parsed, Path xmlPath) {
        for (String name : SpringReflection.getBeanDefinitionNames(parsed)) {
            Object next = SpringReflection.getBeanDefinition(parsed, name);
            String type = next == null ? null : SpringReflection.getBeanClassName(next);
            if (type == null || !type.startsWith(CONFIG) || type.equals(ENDPOINT)) continue;
            Object previous = SpringReflection.getBeanDefinition(live, name);
            if (previous == null || !type.equals(SpringReflection.getBeanClassName(previous))) continue;
            // Only namespace infrastructure (fixed names, one per context) is shared. A generated
            // handler name can belong to another file's endpoint and is never dropped or paired here.
            if (isInfrastructure(type)) SpringReflection.removeBeanDefinition(parsed, name);
        }
    }

    /** Spring's generated bean name: the class name, '#' and a parse-order counter. */
    static boolean generated(String name) {
        return name.indexOf('#') > 0;
    }

    private static boolean isInfrastructure(String type) {
        return type.endsWith("PostProcessor") || type.startsWith(CONFIG + "ChannelInitializer");
    }

    static String handlerName(Object endpoint) {
        try {
            Object values = call(endpoint, "getPropertyValues");
            Object handler = call(values, "getPropertyValue", "handler");
            if (handler == null) return null;
            Object reference = call(handler, "getValue");
            if (reference == null || !reference.getClass().getName().endsWith("RuntimeBeanReference")) return null;
            return (String) call(reference, "getBeanName");
        } catch (ReflectiveOperationException unknown) {
            return null;
        }
    }

    private static Object withHandler(Object endpoint, String handler) throws Exception {
        Object copy = copy(endpoint);
        Object values = call(copy, "getPropertyValues");
        ClassLoader loader = endpoint.getClass().getClassLoader();
        Object reference = Class.forName("org.springframework.beans.factory.config.RuntimeBeanReference", false, loader)
                .getConstructor(String.class).newInstance(handler);
        call(values, "add", "handler", reference);
        return copy;
    }

    static Object copy(Object definition) throws Exception {
        return call(definition, "cloneBeanDefinition");
    }

    static boolean same(Object previous, Object next) {
        try {
            XmlRawValueComparator comparator = new XmlRawValueComparator(next.getClass().getClassLoader());
            return comparator.verifiedStandardDefinition(previous).equals(comparator.verifiedStandardDefinition(next));
        } catch (RuntimeException | ReflectiveOperationException | LinkageError unverifiable) {
            return false;
        }
    }

    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < args.length && fits; i++)
                fits = args[i] == null ? !types[i].isPrimitive() : wrap(types[i]).isInstance(args[i]);
            if (!fits) continue;
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw failure;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        return Object.class;
    }
}
