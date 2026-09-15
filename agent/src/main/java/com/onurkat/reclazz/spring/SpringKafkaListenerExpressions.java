/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Method;

/** Native endpoint registration with expressions bound to the real listener bean. */
final class SpringKafkaListenerExpressions {
    private SpringKafkaListenerExpressions() { }

    static void register(Object processor, Class<?> annotation, Object metadata, Method method,
                         Object delegate, Object owner, String beanName) throws ReflectiveOperationException {
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
                Object resolvedTopics = topics.invoke(processor, metadata);
                Object resolvedPartitions = partitions.invoke(processor, metadata);
                // Infrastructure preflight excludes enhancers, retry topics and
                // proxy receivers. The standard processor retains conversion,
                // group selection, factory lookup and container registration.
                process.invoke(processor, endpoint, metadata, delegate, beanName, resolvedTopics, resolvedPartitions);
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
