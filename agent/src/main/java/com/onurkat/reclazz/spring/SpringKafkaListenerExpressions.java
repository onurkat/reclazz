/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

/** Native endpoint registration with expressions bound to the real listener bean. */
final class SpringKafkaListenerExpressions {
    private SpringKafkaListenerExpressions() { }

    static Object enhance(Object processor, Class<?> annotation, Object metadata, Method method)
            throws ReflectiveOperationException {
        Method enhance = required(processor.getClass(), "enhance", AnnotatedElement.class, annotation);
        try {
            return enhance.invoke(processor, method, (Annotation) metadata);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    record RetryProbe(String[] topics, Object partitions, Object configuration) {
        boolean matching() { return configuration != null; }
    }

    // AddedKafkaListenerAdapter accepts no retry annotation on the class or method. For these
    // listeners a local configuration bean is the only other source used by Spring Kafka.
    static boolean hasRetryConfigurationBeans(Class<?> annotation, Object beanFactory) throws ReflectiveOperationException {
        Class<?> config = Class.forName("org.springframework.kafka.retrytopic.RetryTopicConfiguration", false,
                annotation.getClassLoader());
        String[] names = (String[]) beanFactory.getClass().getMethod("getBeanNamesForType", Class.class)
                .invoke(beanFactory, config);
        return names.length != 0;
    }

    static RetryProbe probeRetryTopicConfiguration(Object processor, Class<?> annotation, Object metadata,
                                                       Method method, Object owner, Object beanFactory)
            throws ReflectiveOperationException {
        synchronized (processor) {
            Object scope = Reflect.readField(processor, "listenerScope");
            if (scope == null) throw new IllegalStateException("Kafka listener expression scope is inaccessible");
            Method get = required(scope.getClass(), "resolveContextualObject", String.class);
            Method add = required(scope.getClass(), "addListener", String.class, Object.class);
            Method remove = required(scope.getClass(), "removeListener", String.class);
            String key = (String) annotation.getMethod("beanRef").invoke(metadata);
            Object previous = get.invoke(scope, key);
            try {
                add.invoke(scope, key, owner);
                String[] topics = (String[]) required(processor.getClass(), "resolveTopics", annotation).invoke(processor, metadata);
                Object partitions = required(processor.getClass(), "resolveTopicPartitions", annotation).invoke(processor, metadata);
                String[] candidates = topics;
                if (topics.length == 0 && partitions != null && partitions.getClass().isArray()) {
                    java.util.LinkedHashSet<String> partitionTopics = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < java.lang.reflect.Array.getLength(partitions); i++) {
                        Object partition = java.lang.reflect.Array.get(partitions, i);
                        partitionTopics.add((String) partition.getClass().getMethod("getTopic").invoke(partition));
                    }
                    candidates = partitionTopics.toArray(String[]::new);
                }
                ClassLoader loader = annotation.getClassLoader();
                Class<?> factoryType = Class.forName("org.springframework.beans.factory.BeanFactory", false, loader);
                Class<?> providerType = Class.forName("org.springframework.kafka.annotation.RetryTopicConfigurationProvider", false, loader);
                Object provider = providerType.getConstructor(factoryType).newInstance(beanFactory);
                Object configuration = required(providerType, "findRetryConfigurationFor", String[].class,
                        Method.class, Object.class).invoke(provider, candidates, method, owner);
                return new RetryProbe(topics, partitions, configuration);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw failure;
            } finally {
                if (previous == null) remove.invoke(scope, key);
                else add.invoke(scope, key, previous);
            }
        }
    }

    static SpringKafkaRetryRegistration register(Object processor, Class<?> annotation, Object metadata, Method method,
                         Object delegate, Object owner, String beanName, RetryProbe probe) throws ReflectiveOperationException {
        // Native processKafkaListener uses this same monitor for its temporary
        // listener scope. Keep other registrations from observing our binding.
        synchronized (processor) {
            Class<?> processorType = processor.getClass();
            Class<?> endpointType = Class.forName("org.springframework.kafka.config.MethodKafkaListenerEndpoint",
                    false, annotation.getClassLoader());
            Method topics = required(processorType, "resolveTopics", annotation);
            Method partitions = required(processorType, "resolveTopicPartitions", annotation);
            Method process = required(processorType, "processListener", endpointType, annotation,
                    Object.class, String.class, String[].class, partitions.getReturnType());
            Object scope = Reflect.readField(processor, "listenerScope");
            if (scope == null) throw new IllegalStateException("Kafka listener expression scope is inaccessible");
            Method get = required(scope.getClass(), "resolveContextualObject", String.class);
            Method add = required(scope.getClass(), "addListener", String.class, Object.class);
            Method remove = required(scope.getClass(), "removeListener", String.class);
            String key = (String) annotation.getMethod("beanRef").invoke(metadata);
            Object endpoint = endpointType.getConstructor().newInstance();
            endpointType.getMethod("setMethod", Method.class).invoke(endpoint, method);
            Object previous = get.invoke(scope, key);
            try {
                add.invoke(scope, key, owner);
                // Kafka 3.2.4 initializes the ID before processListener, unlike 2.9.13.
                if ("3.2.4".equals(processorType.getPackage().getImplementationVersion())) {
                    Object id = required(processorType, "getEndpointId", annotation).invoke(processor, metadata);
                    endpointType.getMethod("setId", String.class).invoke(endpoint, id);
                }
                Object resolvedTopics = probe == null ? topics.invoke(processor, metadata) : probe.topics();
                Object resolvedPartitions = probe == null ? partitions.invoke(processor, metadata) : probe.partitions();
                // Match and register against the same subscription. The processor still
                // owns group selection, conversion, factory lookup and container registration.
                if (probe != null && probe.matching())
                    return SpringKafkaRetryRegistration.register(processor, annotation, metadata, delegate, beanName, endpoint, probe);
                process.invoke(processor, endpoint, metadata, delegate, beanName, resolvedTopics, resolvedPartitions);
                return null;
            } finally {
                if (previous == null) remove.invoke(scope, key);
                else add.invoke(scope, key, previous);
            }
        }
    }

    private static Method required(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = Reflect.findMethod(type, name, parameters);
        if (method == null) throw new NoSuchMethodException("Kafka expression registration requires " + name);
        return method;
    }
}
