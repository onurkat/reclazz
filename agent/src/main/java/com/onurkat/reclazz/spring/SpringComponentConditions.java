/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;

import java.lang.reflect.Proxy;

/** Native condition evaluation using the application's Spring and current context. */
final class SpringComponentConditions {
    private SpringComponentConditions() { }

    static boolean shouldSkip(Object definition, Object home, Object beanFactory,
                              ClassLoader applicationLoader, ClassLoader springLoader) throws ReflectiveOperationException {
        Object metadata = definition.getClass().getMethod("getMetadata").invoke(definition);
        // Components participate in parsing and registration, unlike Bean methods.
        return shouldSkipMetadata(metadata, home, beanFactory, applicationLoader, springLoader,
                "PARSE_CONFIGURATION", "REGISTER_BEAN");
    }

    static boolean shouldSkipMetadata(Object metadata, Object home, Object beanFactory,
                                      ClassLoader applicationLoader, ClassLoader springLoader,
                                      String... phases) throws ReflectiveOperationException {
        Class<?> metadataType = Class.forName("org.springframework.core.type.AnnotatedTypeMetadata", false, springLoader);
        if (!Boolean.TRUE.equals(metadataType.getMethod("isAnnotated", String.class)
                .invoke(metadata, "org.springframework.context.annotation.Conditional"))) return false;

        Class<?> registryType = Class.forName("org.springframework.beans.factory.support.BeanDefinitionRegistry", false, springLoader);
        Class<?> environmentType = Class.forName("org.springframework.core.env.Environment", false, springLoader);
        Class<?> resourceType = Class.forName("org.springframework.core.io.ResourceLoader", false, springLoader);
        Object environment = home.getClass().getMethod("getEnvironment").invoke(home);
        // Keep application protocol resolvers, but do not let the watcher's TCCL
        // replace the application loader captured by Spring at refresh.
        Object resources = Proxy.newProxyInstance(springLoader, new Class<?>[]{resourceType}, (proxy, method, args) -> {
            if (method.getName().equals("getClassLoader")) return applicationLoader;
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "Reclazz component condition resources";
                };
            }
            // DefaultResourceLoader consults the caller's TCCL unless the context
            // has an explicit loader. Preserve context resolvers and path policy
            // while making their default classpath lookup use the application.
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(applicationLoader);
                return method.invoke(home, args);
            } finally {
                thread.setContextClassLoader(previous);
            }
        });

        // ConditionEvaluator is internal in both Spring 5.3 and 6.1. Any missing
        // or inaccessible API propagates to the registrar's decline handler;
        // a conditional component must never fall back to unconditional registration.
        Class<?> evaluatorType = Class.forName("org.springframework.context.annotation.ConditionEvaluator", true, springLoader);
        var constructor = evaluatorType.getDeclaredConstructor(registryType, environmentType, resourceType);
        constructor.setAccessible(true);
        Object evaluator = constructor.newInstance(beanFactory, environment, resources);
        Class<?> phaseType = Class.forName("org.springframework.context.annotation.ConfigurationCondition$ConfigurationPhase", false, springLoader);
        var shouldSkip = Reflect.findMethod(evaluatorType, "shouldSkip", metadataType, phaseType);
        if (shouldSkip == null) throw new NoSuchMethodException("ConditionEvaluator.shouldSkip(metadata, phase)");
        // The single-argument overload infers PARSE_CONFIGURATION for components.
        // Registration-phase conditions (including bean-presence checks) need
        // the second pass that startup's configuration reader normally performs.
        for (String phase : phases) {
            if (Boolean.TRUE.equals(shouldSkip.invoke(evaluator, metadata, phaseType.getField(phase).get(null)))) return true;
        }
        return false;
    }
}
