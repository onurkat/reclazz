/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.lang.reflect.Method;
import java.util.*;

/** Bounded snapshots of declared Spring values, never of the live application object graph. */
final class XmlRawValueComparator {
    private final ClassLoader springLoader;
    private final Class<?> definitionType, holderType, mergeableType;
    private int nodes;

    XmlRawValueComparator(ClassLoader springLoader) throws ClassNotFoundException {
        this.springLoader = springLoader;
        definitionType = type("org.springframework.beans.factory.config.BeanDefinition");
        holderType = type("org.springframework.beans.factory.config.BeanDefinitionHolder");
        mergeableType = type("org.springframework.beans.Mergeable");
    }
    private record Value(String type, Object content, Object metadata) { }
    private record Snapshot(Object value, List<Object> innerDefinitions) { }

    boolean same(Object before, Object after, ClassLoader applicationLoader) throws ReflectiveOperationException {
        Snapshot oldValue = inspect(before), newValue = inspect(after);
        if (oldValue.value.equals(newValue.value)) return true;
        // Resolving a changed list/map reconstructs all its inner values, including
        // unchanged resources. Check both old and new compositions before any setter.
        for (Object definition : oldValue.innerDefinitions) requireSimpleInner(definition, applicationLoader);
        for (Object definition : newValue.innerDefinitions) requireSimpleInner(definition, applicationLoader);
        return false;
    }
    Object semantic(Object raw) throws ReflectiveOperationException { return inspect(raw).value; }

    private Snapshot inspect(Object raw) throws ReflectiveOperationException {
        nodes = 0;
        List<Object> innerDefinitions = new ArrayList<>();
        return new Snapshot(value(raw, 0, innerDefinitions), innerDefinitions);
    }
    private Value value(Object raw, int depth, List<Object> inners) throws ReflectiveOperationException {
        if (depth > 32 || ++nodes > 4096) throw new IllegalArgumentException("raw value depth/size exceeds verification bound");
        if (raw == null) return new Value("null", null, null);
        if (holderType.isInstance(raw)) return value(call(raw, "getBeanDefinition"), depth + 1, inners);
        if (definitionType.isInstance(raw)) {
            inners.add(raw);
            Object header = call(raw, "cloneBeanDefinition");
            set(header, "setBeanClassName", String.class, call(raw, "getBeanClassName"));
            set(header, "setScope", String.class, normalizedScope((String) call(raw, "getScope")));
            set(header, "setSource", Object.class, null);
            set(header, "setPropertyValues", type("org.springframework.beans.MutablePropertyValues"),
                    type("org.springframework.beans.MutablePropertyValues").getConstructor().newInstance());
            set(header, "setConstructorArgumentValues", type("org.springframework.beans.factory.config.ConstructorArgumentValues"),
                    type("org.springframework.beans.factory.config.ConstructorArgumentValues").getConstructor().newInstance());
            Map<String,Object> properties = new LinkedHashMap<>();
            for (Object pv : (Object[]) call(call(raw, "getPropertyValues"), "getPropertyValues")) {
                properties.put((String) call(pv, "getName"), Arrays.asList(call(pv, "isOptional"),
                        value(call(pv, "getValue"), depth + 1, inners)));
            }
            Object arguments = call(raw, "getConstructorArgumentValues");
            Map<Object,Object> indexed = new LinkedHashMap<>();
            for (var entry : ((Map<?,?>) call(arguments, "getIndexedArgumentValues")).entrySet())
                indexed.put(entry.getKey(), argument(entry.getValue(), depth + 1, inners));
            List<Object> generic = new ArrayList<>();
            for (Object holder : (List<?>) call(arguments, "getGenericArgumentValues"))
                generic.add(argument(holder, depth + 1, inners));
            return new Value("inner", Arrays.asList(header, properties, indexed, generic), null);
        }
        String name = raw.getClass().getName();
        if (name.equals("org.springframework.beans.factory.config.TypedStringValue"))
            return new Value("typed", call(raw, "getValue"), call(raw, "getTargetTypeName"));
        if (name.equals("org.springframework.beans.factory.config.RuntimeBeanReference")) {
            Class<?> beanType = (Class<?>) call(raw, "getBeanType");
            return new Value("ref", Arrays.asList(call(raw, "getBeanName"), beanType == null ? null : beanType.getName()), call(raw, "isToParent"));
        }
        if (name.equals("org.springframework.beans.factory.config.RuntimeBeanNameReference"))
            return new Value("ref-name", call(raw, "getBeanName"), null);
        List<Object> metadata = new ArrayList<>();
        if (mergeableType.isInstance(raw)) metadata.add(call(raw, "isMergeEnabled"));
        if (name.equals("org.springframework.beans.factory.support.ManagedList")
                || name.equals("org.springframework.beans.factory.support.ManagedSet")
                || name.equals("org.springframework.beans.factory.support.ManagedArray")) metadata.add(call(raw, "getElementTypeName"));
        if (name.equals("org.springframework.beans.factory.support.ManagedMap")) {
            metadata.add(call(raw, "getKeyTypeName")); metadata.add(call(raw, "getValueTypeName"));
        }
        if (raw instanceof Map<?,?> map) {
            List<Object> entries = new ArrayList<>();
            for (var entry : map.entrySet()) entries.add(Arrays.asList(value(entry.getKey(), depth + 1, inners), value(entry.getValue(), depth + 1, inners)));
            return new Value(name, entries, metadata);
        }
        if (raw instanceof Collection<?> collection) {
            List<Object> entries = new ArrayList<>();
            for (Object item : collection) entries.add(value(item, depth + 1, inners));
            return new Value(name, entries, metadata);
        }
        if (raw instanceof String || raw instanceof Boolean || raw instanceof Character
                || Set.of("java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                        "java.lang.Float", "java.lang.Double", "java.math.BigInteger", "java.math.BigDecimal").contains(name))
            return new Value(name, raw, null);
        throw new IllegalArgumentException("unsupported raw value type: " + name);
    }
    private Object argument(Object holder, int depth, List<Object> inners) throws ReflectiveOperationException {
        return Arrays.asList(call(holder, "getName"), call(holder, "getType"), value(call(holder, "getValue"), depth, inners));
    }

    private void requireSimpleInner(Object definition, ClassLoader loader) throws ReflectiveOperationException {
        require(!(Boolean) call(definition, "isAbstract") && (Boolean) call(definition, "isSingleton"), "inner scope/template");
        require(call(definition, "getParentName") == null, "inner parent definition");
        require(empty(call(definition, "getFactoryBeanName")) && empty(call(definition, "getFactoryMethodName")), "inner factory construction");
        require(empty(call(definition, "getInitMethodName")) && empty(call(definition, "getDestroyMethodName")), "inner lifecycle callback");
        require((Integer) call(definition, "getAutowireMode") == 0, "inner autowire policy");
        String[] depends = (String[]) call(definition, "getDependsOn");
        require(depends == null || depends.length == 0, "inner depends-on policy");
        require((Integer) call(definition, "getRole") == 0 && !(Boolean) call(definition, "isSynthetic"), "inner infrastructure definition");
        require(call(definition, "getInstanceSupplier") == null && (Boolean) call(call(definition, "getMethodOverrides"), "isEmpty"), "inner custom construction");
        String className = (String) call(definition, "getBeanClassName");
        require(className != null, "inner class unresolved");
        Class<?> beanType = Class.forName(className, false, loader);
        for (String name : List.of("java.lang.AutoCloseable", "java.lang.Thread", "java.util.concurrent.Executor", "javax.sql.DataSource",
                "org.springframework.beans.factory.FactoryBean", "org.springframework.beans.factory.InitializingBean", "org.springframework.beans.factory.DisposableBean",
                "org.springframework.beans.factory.config.BeanPostProcessor", "org.springframework.beans.factory.config.BeanFactoryPostProcessor",
                "org.springframework.context.ApplicationContext", "org.springframework.context.Lifecycle", "org.springframework.context.ApplicationListener")) {
            Class<?> forbidden = Class.forName(name, false, loader);
            require(!forbidden.isAssignableFrom(beanType), "inner resource/infrastructure/lifecycle type: " + name);
        }
        for (Class<?> current = beanType; current != null && current != Object.class; current = current.getSuperclass())
            for (Method method : current.getDeclaredMethods()) for (var annotation : method.getDeclaredAnnotations())
                require(!Set.of("javax.annotation.PostConstruct", "javax.annotation.PreDestroy", "jakarta.annotation.PostConstruct", "jakarta.annotation.PreDestroy")
                        .contains(annotation.annotationType().getName()), "inner lifecycle annotation");
    }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, springLoader); }
    private static Object call(Object object, String method) throws ReflectiveOperationException { return object.getClass().getMethod(method).invoke(object); }
    private static void set(Object object, String method, Class<?> type, Object value) throws ReflectiveOperationException {
        object.getClass().getMethod(method, type).invoke(object, new Object[]{value});
    }
    private static String normalizedScope(String scope) { return scope == null || scope.isEmpty() ? "singleton" : scope; }
    private static boolean empty(Object value) { return value == null || value.equals(""); }
    private static void require(boolean accepted, String reason) {
        if (!accepted) throw new IllegalArgumentException("unsupported inner bean replacement: " + reason);
    }
}
