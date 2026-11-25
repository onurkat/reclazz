/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Compatibility results belong to the defining Spring class, never to a live bean or context. */
public enum SpringMemberLayout {
    CONSTRUCTOR, PROXY, XML, RAW_CONFIGURATION, RESET_CALLBACK;

    private static final String SUPPORT = "org.springframework.beans.factory.support.";
    private final ClassValue<String> failures = new ClassValue<>() {
        @Override protected String computeValue(Class<?> type) {
            try {
                inspect(type);
                return "";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                return type.getName() + ": " + failure;
            }
        }
    };

    static final class Unavailable extends IllegalStateException {
        Unavailable(String failure) { super(failure); }
    }

    public void require(Class<?> type) {
        String failure = failures.get(type);
        if (!failure.isEmpty()) throw new Unavailable(failure);
    }

    private void inspect(Class<?> type) throws ReflectiveOperationException {
        switch (this) {
            case CONSTRUCTOR -> {
                method(type, "clearMergedBeanDefinition", void.class, String.class);
                Class<?> root = load(type, SUPPORT + "RootBeanDefinition");
                field(root, "resolvedConstructorOrFactoryMethod", Executable.class);
                field(root, "resolvedConstructorArguments", Object[].class);
                field(root, "preparedConstructorArguments", Object[].class);
            }
            case PROXY -> {
                field(type, "disposableBeans", Map.class);
                field(type, "containedBeanMap", Map.class);
                field(type, "singletonsCurrentlyInDestruction", boolean.class);
                method(type, "clearMergedBeanDefinition", void.class, String.class);
                Class<?> creator = load(type, "org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator");
                field(creator, "advisedBeans", Map.class);
                method(creator, "getCacheKey", Object.class, Class.class, String.class);
                method(creator, "getAdvicesAndAdvisorsForBean", Object[].class, Class.class, String.class,
                        load(type, "org.springframework.aop.TargetSource"));
                Class<?> advising = load(type, "org.springframework.aop.framework.AbstractAdvisingBeanPostProcessor");
                field(advising, "eligibleBeans", Map.class);
                method(advising, "isEligible", boolean.class, Class.class);
                witnessCreationLock(type);
            }
            case XML -> {
                field(type, "beanDefinitionMap", Map.class);
                field(type, "mergedBeanDefinitions", Map.class);
                field(type, "factoryBeanObjectCache", Map.class);
                field(type, "alreadyCreated", Set.class);
                field(type, "singletonsCurrentlyInDestruction", boolean.class);
                field(load(type, SUPPORT + "RootBeanDefinition"), "stale", boolean.class);
                method(type, "clearByTypeCache", void.class);
                method(type, "clearMergedBeanDefinition", void.class, String.class);
                witnessCreationLock(type);
            }
            case RAW_CONFIGURATION -> {
                method(type, "getAttribute", Object.class, String.class);
                method(type, "getRole", int.class);
                method(type, "getBeanClass", Class.class);
                method(type, "getFactoryBeanName", String.class);
                method(type, "getFactoryMethodName", String.class);
                Field key = field(load(type, "org.springframework.context.annotation.ConfigurationClassUtils"),
                        "CONFIGURATION_CLASS_ATTRIBUTE", String.class);
                if (!Modifier.isStatic(key.getModifiers())) throw new NoSuchFieldException(key + " must be static");
            }
            case RESET_CALLBACK -> witnessReset(type);
        }
    }

    private static Class<?> load(Class<?> type, String name) throws ClassNotFoundException {
        return Class.forName(name, false, type.getClassLoader());
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws NoSuchFieldException {
        Field field = Reflect.findField(type, name);
        if (field == null || !expected.isAssignableFrom(field.getType()))
            throw new NoSuchFieldException(type.getSimpleName() + "." + name + " requires " + expected.getTypeName());
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?> expected, Class<?>... args)
            throws NoSuchMethodException {
        Method method = Reflect.findMethod(type, name, args);
        if (method == null || !expected.isAssignableFrom(method.getReturnType()) || method.getReturnType().isPrimitive() != expected.isPrimitive())
            throw new NoSuchMethodException(type.getSimpleName() + "." + name + Arrays.toString(args)
                    + " requires " + expected.getTypeName());
        return method;
    }

    // A field named singletonLock alone does not establish that native creation takes it.
    private static void witnessCreationLock(Class<?> type) throws ReflectiveOperationException {
        if (!type.getName().equals(SUPPORT + "DefaultListableBeanFactory"))
            throw new IllegalStateException("singleton lock requires the native DefaultListableBeanFactory");
        Object factory = type.getConstructor().newInstance();
        Field lockField = Reflect.findField(type, "singletonLock");
        Object mutex = lockField == null ? method(type, "getSingletonMutex", Object.class).invoke(factory) : lockField.get(factory);
        if (mutex == null || (lockField != null && !(mutex instanceof ReentrantLock)))
            throw new IllegalStateException("singletonLock/getSingletonMutex layout unavailable");
        Class<?> supplier = load(type, "org.springframework.beans.factory.ObjectFactory");
        Object bean = new Object();
        Object callback = Proxy.newProxyInstance(supplier.getClassLoader(), new Class<?>[] {supplier},
                (proxy, invoked, args) -> {
                    boolean held = lockField == null ? Thread.holdsLock(mutex) : ((ReentrantLock) mutex).isHeldByCurrentThread();
                    return held && invoked.getName().equals("getObject") ? bean : null;
                });
        Object created = method(type, "getSingleton", Object.class, String.class, supplier)
                .invoke(factory, "reclazzLockWitness", callback);
        if (created != bean) throw new IllegalStateException("getSingleton did not hold singletonLock/getSingletonMutex");
    }

    // Only known native processors are instantiated. Application callbacks are never probed.
    @SuppressWarnings("unchecked")
    private static void witnessReset(Class<?> type) throws ReflectiveOperationException {
        String name = type.getName();
        boolean autowired = name.equals("org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor");
        if (!autowired && !name.equals("org.springframework.context.annotation.CommonAnnotationBeanPostProcessor")
                && !name.equals("org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor"))
            throw new IllegalStateException("unverified resetBeanDefinition implementation");
        Object processor = type.getConstructor().newInstance();
        Map<String, Object> cache = (Map<String, Object>) field(type, "injectionMetadataCache", Map.class).get(processor);
        String drop = "reclazzResetWitness", retain = "reclazzRetainedWitness";
        Object marker = new Object();
        cache.put(drop, marker);
        cache.put(retain, marker);
        Set<String> lookups = autowired ? (Set<String>) field(type, "lookupMethodsChecked", Set.class).get(processor) : null;
        if (lookups != null) lookups.addAll(List.of(drop, retain));
        method(type, "resetBeanDefinition", void.class, String.class).invoke(processor, drop);
        if (cache.containsKey(drop) || cache.get(retain) != marker
                || (lookups != null && (lookups.contains(drop) || !lookups.contains(retain))))
            throw new IllegalStateException("resetBeanDefinition did not invalidate only the named entry");
    }
}
