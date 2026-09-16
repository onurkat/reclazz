/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.util.Reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Native factory parameter metadata, shared by candidate checking and recreation. */
final class SpringFactoryValues {
    private static final Set<Class<?>> SCALARS = Set.of(String.class, Boolean.class, Character.class,
            Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class);
    private static final String PROPERTIES = "org.springframework.boot.context.properties.ConfigurationProperties";

    record Values(List<SpringPropertyRebinder.ValueTarget> targets) { }

    /** Null means there is no resolved factory @Value declaration to own this bean. */
    static Values inspect(Object factory, String name, Object singleton,
            Class<? extends Annotation> annotation, Method value, PropertyValueDependencies affected) throws Exception {
        if (!Boolean.TRUE.equals(PropertyChangeCheck.call(factory, "containsBeanDefinition", name))) return null;
        Object definition = PropertyChangeCheck.call(factory, "getMergedBeanDefinition", name);
        Values added = SpringAddedFactoryValues.inspect(factory, definition, name, singleton, annotation, value, affected);
        if (added != null) return added;
        if (PropertyChangeCheck.call(definition, "getFactoryMethodName") == null) return null;
        Object resolved = PropertyChangeCheck.call(definition, "getResolvedFactoryMethod");
        if (!(resolved instanceof Method)) resolved = Reflect.readField(definition, "resolvedConstructorOrFactoryMethod");
        if (!(resolved instanceof Method method)) return null;
        var parameters = method.getParameters();
        List<SpringPropertyRebinder.ValueTarget> targets = new ArrayList<>();
        boolean selected = false;
        for (int i = 0; i < parameters.length; i++) {
            var found = parameters[i].getAnnotation(annotation);
            if (found == null) continue;
            String expression = (String) value.invoke(found);
            selected |= affected.test(expression);
            targets.add(new SpringPropertyRebinder.ValueTarget(singleton, null, parameters[i].getType(),
                    expression, name + "." + method.getName() + "[" + i + "]", null));
        }
        if (targets.isEmpty()) return null;
        if (!selected) return new Values(List.of());
        String problem = policy(factory, definition, singleton, method, annotation.getClassLoader());
        return checked(targets, problem);
    }

    static Values checked(List<SpringPropertyRebinder.ValueTarget> targets, String problem) {
        List<SpringPropertyRebinder.ValueTarget> checked = new ArrayList<>();
        for (var target : targets) {
            String reason = problem;
            if (reason == null && !target.type().isPrimitive() && !SCALARS.contains(target.type()))
                reason = "factory @Value parameters require primitive, boxed primitive or String values";
            checked.add(new SpringPropertyRebinder.ValueTarget(target.bean(), null, target.type(),
                    target.expression(), target.member(), reason));
        }
        return new Values(List.copyOf(checked));
    }

    /** Read caches only; never resolve a bean or execute the factory for a precheck. */
    private static String policy(Object factory, Object definition, Object singleton, Method method, ClassLoader loader) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> bean = (Class<? extends Annotation>) Class.forName(
                    "org.springframework.context.annotation.Bean", false, loader);
            if (!method.isAnnotationPresent(bean)) return "factory values require a direct @Bean method";
            if (method.getTypeParameters().length != 0) return "generic factory methods are unsupported";
            String expressionProblem = expressionPolicy(factory);
            if (expressionProblem != null) return expressionProblem;
            if (!Boolean.TRUE.equals(PropertyChangeCheck.call(definition, "isSingleton")))
                return "factory values require singleton scope";
            Object target = PropertyTransactionProxy.target(singleton, loader);
            if (Class.forName("org.springframework.beans.factory.FactoryBean", false, loader).isInstance(target))
                return "FactoryBean products use a separate creation policy";
            if (!method.getReturnType().isInstance(target))
                return "factory method return type does not describe the live product";
            if (hasProperties(method.getAnnotations()) || hasProperties(target.getClass().getAnnotations()))
                return "factory @ConfigurationProperties uses its own binding path";
            if (PropertyChangeCheck.call(definition, "getInstanceSupplier") != null)
                return "factory instance suppliers are unsupported";
            if (Boolean.TRUE.equals(PropertyChangeCheck.call(definition, "hasConstructorArgumentValues"))
                    || Boolean.TRUE.equals(PropertyChangeCheck.call(definition, "hasMethodOverrides")))
                return "explicit factory arguments or method overrides are unsupported";
            if (!method.getName().equals(PropertyChangeCheck.call(definition, "getFactoryMethodName")))
                return "factory method name no longer agrees with its definition";
            Object ownerName = PropertyChangeCheck.call(definition, "getFactoryBeanName");
            if (Modifier.isStatic(method.getModifiers()) != (ownerName == null))
                return "factory static/instance creation policy does not agree";
            if (ownerName != null) {
                Object owner = PropertyChangeCheck.call(factory, "getSingleton", ownerName);
                if (owner == null || !method.getDeclaringClass().isInstance(owner) || isProxy(owner, loader))
                    return "factory owner must be an existing singleton without extra AOP advice";
            }
            if (!method.equals(Reflect.readField(definition, "resolvedConstructorOrFactoryMethod"))
                    || !Boolean.TRUE.equals(Reflect.readField(definition, "constructorArgumentsResolved"))
                    || Reflect.readField(definition, "resolvedConstructorArguments") != null
                    || !(Reflect.readField(definition, "preparedConstructorArguments") instanceof Object[] prepared)
                    || prepared.length != method.getParameterCount())
                return "Spring's re-resolvable factory arguments could not be verified";
            // Spring 5.3.39 re-resolves ConstructorDependencyDescriptor entries.
            // A cached constant (even inside a prepared array) would stay stale.
            for (int i = 0; i < prepared.length; i++) {
                Object argument = prepared[i];
                if (argument == null || !argument.getClass().getName().equals(
                        "org.springframework.beans.factory.support.ConstructorResolver$ConstructorDependencyDescriptor"))
                    return "factory argument cache contains a value that is not re-resolvable";
                Object parameter = PropertyChangeCheck.call(argument, "getMethodParameter");
                if (parameter == null || !method.equals(PropertyChangeCheck.call(parameter, "getExecutable"))
                        || !Integer.valueOf(i).equals(PropertyChangeCheck.call(parameter, "getParameterIndex")))
                    return "factory argument cache does not describe the resolved method";
            }
            return null;
        } catch (Throwable failure) {
            return "factory creation policy could not be verified: " + Failures.describe(failure);
        }
    }

    static String expressionPolicy(Object factory) throws Exception {
        // Even a direct placeholder is passed to this resolver on native
        // recreation. A custom resolver need not obey standard delimiters.
        Object resolver = PropertyChangeCheck.call(factory, "getBeanExpressionResolver");
        Object parser = resolver == null ? null : Reflect.readField(resolver, "expressionParser");
        if (resolver == null || !resolver.getClass().getName().equals(
                "org.springframework.context.expression.StandardBeanExpressionResolver")
                || !"#{".equals(Reflect.readField(resolver, "expressionPrefix"))
                || !"}".equals(Reflect.readField(resolver, "expressionSuffix"))
                || parser == null || !parser.getClass().getName().equals(
                "org.springframework.expression.spel.standard.SpelExpressionParser"))
            return "factory values require the standard bean expression resolver and parser";
        return null;
    }

    static boolean isProxy(Object bean, ClassLoader loader) throws Exception {
        if (java.lang.reflect.Proxy.isProxyClass(bean.getClass())) return true;
        return (boolean) Class.forName("org.springframework.aop.support.AopUtils", false, loader)
                .getMethod("isAopProxy", Object.class).invoke(null, bean);
    }

    static boolean hasProperties(Annotation[] annotations) {
        for (Annotation annotation : annotations)
            if (annotation.annotationType().getName().equals(PROPERTIES)) return true;
        return false;
    }
}
