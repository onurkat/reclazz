/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.Failures;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.function.Supplier;

/** Factory arguments resolved by the application's own Spring, with saved generic metadata. */
final class AddedBeanArguments {
    private AddedBeanArguments() { }

    static Supplier<Object[]> prepare(Object factory, String beanName, Method metadata, MethodHandle descriptorConstructor) {
        if (metadata.getParameterCount() == 0) return () -> new Object[0];
        try {
            ClassLoader spring = factory.getClass().getClassLoader();
            for (var parameter : metadata.getParameters()) requireSupported(parameter, spring);
            Class<?> parameterType = Class.forName("org.springframework.core.MethodParameter", false, spring);
            Class<?> descriptorType = Class.forName("org.springframework.beans.factory.config.DependencyDescriptor", false, spring);
            Class<?> discovererType = Class.forName("org.springframework.core.ParameterNameDiscoverer", false, spring);
            Class<?> converterType = Class.forName("org.springframework.beans.TypeConverter", false, spring);
            Method resolve = factory.getClass().getMethod("resolveDependency", descriptorType, String.class, Set.class, converterType);
            Method register = factory.getClass().getMethod("registerDependentBean", String.class, String.class);
            String[] names = Arrays.stream(metadata.getParameters()).map(p -> p.isNamePresent() ? p.getName() : null).toArray(String[]::new);
            Object discoverer = Proxy.newProxyInstance(spring, new Class<?>[]{discovererType}, (proxy, method, args) -> {
                if (method.getName().equals("getParameterNames")) return names.clone();
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Reclazz factory parameter names";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            return () -> {
                Object[] arguments = new Object[metadata.getParameterCount()];
                Set<String> dependencies = new LinkedHashSet<>();
                for (int i = 0; i < arguments.length; i++) {
                    try {
                        Object parameter = parameterType.getConstructor(Method.class, int.class).newInstance(metadata, i);
                        parameterType.getMethod("initParameterNameDiscovery", discovererType).invoke(parameter, discoverer);
                        Object descriptor = descriptorConstructor.invoke(parameter, true);
                        try { arguments[i] = resolve.invoke(factory, descriptor, beanName, dependencies, null); }
                        catch (InvocationTargetException failure) {
                            if (!failure.getCause().getClass().getName().equals("org.springframework.beans.factory.NoSuchBeanDefinitionException")) throw failure;
                            Object empty = emptyContainer(metadata.getParameterTypes()[i]);
                            if (empty == null) throw failure;
                            arguments[i] = empty;
                        }
                        if (!isValue(metadata.getParameters()[i])) Objects.requireNonNull(arguments[i], "required dependency resolved to null");
                    } catch (Throwable failure) {
                        Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
                        throw new IllegalStateException("cannot resolve parameter " + i + " of added bean '" + beanName
                                + "': " + Failures.describe(cause), cause);
                    }
                }
                try {
                    for (String dependency : dependencies) register.invoke(factory, dependency, beanName);
                } catch (Exception failure) { throw new IllegalStateException("cannot record dependencies of added bean '" + beanName + "'", failure); }
                return arguments;
            };
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Spring factory argument resolution is unavailable", failure);
        }
    }

    private static boolean isValue(java.lang.reflect.Parameter parameter) {
        return Arrays.stream(parameter.getAnnotations()).anyMatch(a -> a.annotationType().getName()
                .equals("org.springframework.beans.factory.annotation.Value"));
    }

    private static Object emptyContainer(Class<?> type) {
        if (type.isArray()) return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        if (type == List.class) return new ArrayList<>();
        if (type == Set.class || type == Collection.class) return new LinkedHashSet<>();
        if (type == Map.class) return new LinkedHashMap<>();
        return null;
    }

    private static void requireSupported(java.lang.reflect.Parameter parameter, ClassLoader spring) throws ClassNotFoundException {
        Class<?> type = parameter.getType();
        if (type.isPrimitive() && !isValue(parameter)
                || type.isArray() && (type.getComponentType().isPrimitive() || type.getComponentType().isArray())
                || Collection.class.isAssignableFrom(type) && !Set.of(List.class, Set.class, Collection.class).contains(type)
                || Map.class.isAssignableFrom(type) && type != Map.class
                || java.util.stream.BaseStream.class.isAssignableFrom(type) || Supplier.class.isAssignableFrom(type))
            throw new IllegalArgumentException("unsupported factory parameter type: " + type.getName());
        if (type.getTypeParameters().length > 0 && !(parameter.getParameterizedType() instanceof java.lang.reflect.ParameterizedType))
            throw new IllegalArgumentException("raw generic factory parameters are unsupported: " + type.getName());
        if (type == Map.class && ((java.lang.reflect.ParameterizedType) parameter.getParameterizedType()).getActualTypeArguments()[0] != String.class)
            throw new IllegalArgumentException("bean maps require String keys");
        Set<String> providers = Set.of("org.springframework.beans.factory.ObjectFactory", "org.springframework.beans.factory.ObjectProvider");
        for (String name : providers)
            if (!providers.contains(type.getName()) && Class.forName(name, false, spring).isAssignableFrom(type))
                throw new IllegalArgumentException("custom provider interfaces are unsupported: " + type.getName());
        for (String name : List.of("javax.inject.Provider", "jakarta.inject.Provider")) {
            try {
                if (Class.forName(name, false, spring).isAssignableFrom(type))
                    throw new IllegalArgumentException("provider factory parameters are not supported: " + type.getName());
            } catch (ClassNotFoundException absent) { /* optional injection API */ }
        }
    }
}
