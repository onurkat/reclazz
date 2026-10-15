/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Named constructor-cache repair, not a dependent-bean or consumer lifecycle coordinator. */
final class SpringConstructorDependencies {
    private static final String FACTORY =
            "org.springframework.beans.factory.support.DefaultListableBeanFactory";
    private static final Set<String> DEFINITIONS = Set.of(
            "org.springframework.beans.factory.support.RootBeanDefinition",
            "org.springframework.beans.factory.support.GenericBeanDefinition",
            "org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition",
            "org.springframework.context.annotation.ScannedGenericBeanDefinition",
            "org.springframework.context.support.GenericApplicationContext$ClassDerivedBeanDefinition");

    private SpringConstructorDependencies() { }

    /**
     * Read-only eligibility check. Unsupported creation policies retain their existing reload path;
     * this does not authorize an indirect cascade through them.
     */
    static Prepared prepare(List<Object> contexts, Object context, String name) {
        try {
            if (contexts.size() != 1 || contexts.get(0) != context || call(context, "getParent") != null)
                return null;
            Object factory = SpringBeans.getBeanFactory(context);
            if (factory == null || !factory.getClass().getName().equals(FACTORY)) return null;
            String version = factory.getClass().getPackage().getImplementationVersion();
            if (!"5.3.39".equals(version) && !"6.1.14".equals(version)) return null;
            if (call(factory, "getParentBeanFactory") != null
                    || !Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))) return null;
            Object raw = call(factory, "getBeanDefinition", name);
            if (!DEFINITIONS.contains(raw.getClass().getName()) || !plain(raw)) return null;
            Object singleton = call(factory, "getSingleton", name);
            if (singleton == null) return null;
            Class<?> type = singleton.getClass();
            if (!type.getName().equals(call(raw, "getBeanClassName"))) return null;
            Class<?> factoryBean = Class.forName("org.springframework.beans.factory.FactoryBean", false,
                    factory.getClass().getClassLoader());
            if (factoryBean.isInstance(singleton)) return null;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length != 1 || constructors[0].getParameterCount() == 0) return null;
            Object merged = call(factory, "getMergedBeanDefinition", name);
            if (!merged.getClass().getName().equals("org.springframework.beans.factory.support.RootBeanDefinition")
                    || !plain(merged)) return null;
            if (!(Reflect.readField(merged, "resolvedConstructorOrFactoryMethod") instanceof Constructor<?> cached)
                    || !cached.equals(constructors[0])
                    || !Arrays.equals(cached.getDeclaredAnnotations(), constructors[0].getDeclaredAnnotations())
                    || !Arrays.deepEquals(cached.getParameterAnnotations(), constructors[0].getParameterAnnotations())
                    || Reflect.readField(merged, "resolvedConstructorArguments") != null
                    || !(Reflect.readField(merged, "preparedConstructorArguments") instanceof Object[] prepared)
                    || prepared.length != cached.getParameterCount()) return null;
            Method invalidate = Reflect.findMethod(factory.getClass(), "clearMergedBeanDefinition", String.class);
            return invalidate == null ? null : new Prepared(List.copyOf(contexts), context,
                    factory, name, singleton, merged, invalidate);
        } catch (Exception | LinkageError unavailable) {
            return null;
        }
    }

    private static boolean plain(Object definition) throws Exception {
        return Boolean.TRUE.equals(call(definition, "isSingleton"))
                && !Boolean.TRUE.equals(call(definition, "isAbstract"))
                && call(definition, "getParentName") == null
                && call(definition, "getFactoryMethodName") == null
                && call(definition, "getFactoryBeanName") == null
                && call(definition, "getInstanceSupplier") == null
                && Boolean.FALSE.equals(call(definition, "hasConstructorArgumentValues"))
                && Boolean.FALSE.equals(call(definition, "hasMethodOverrides"))
                && call(definition, "getQualifiers") instanceof Set<?> qualifiers && qualifiers.isEmpty();
    }

    private static Object call(Object target, String name, String... argument) throws Exception {
        Method method = argument.length == 0 ? Reflect.findMethod(target.getClass(), name)
                : Reflect.findMethod(target.getClass(), name, String.class);
        if (method == null) throw new NoSuchMethodException(name);
        return argument.length == 0 ? method.invoke(target) : method.invoke(target, argument[0]);
    }

    record Prepared(List<Object> contexts, Object context, Object factory, String name,
                    Object singleton, Object merged, Method invalidate) {
        void apply() throws Exception {
            Prepared current = prepare(contexts, context, name);
            if (current == null || current.factory != factory || current.singleton != singleton
                    || current.merged != merged)
                throw new IllegalStateException("constructor owner changed after preflight: " + name);
            // The next native resolution discovers the CURRENT dependencies. Never replay old edge names.
            invalidate.invoke(factory, name);
        }
    }
}
