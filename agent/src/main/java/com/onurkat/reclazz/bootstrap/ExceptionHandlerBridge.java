/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/** Metadata/receiver substitution confined to Spring MVC's exception resolver. */
public final class ExceptionHandlerBridge {
    private ExceptionHandlerBridge() { }
    private static final class Slot { volatile Schema schema; volatile boolean touched; }
    private record Schema(Class<?> owner, Class<?> type, MethodHandle factory, MethodHandle[] invokers) { }
    private record Endpoint(Class<?> owner, MethodHandle target) { }
    private static final class EndpointSlot { volatile Endpoint endpoint; }
    private static final ClassValue<EndpointSlot> endpoints = new ClassValue<>() {
        @Override protected EndpointSlot computeValue(Class<?> type) { return new EndpointSlot(); }
    };
    private static final ClassValue<Slot> owners = new ClassValue<>() {
        @Override protected Slot computeValue(Class<?> type) { return new Slot(); }
    };
    private static final ClassValue<Slot> schemas = new ClassValue<>() {
        @Override protected Slot computeValue(Class<?> type) { return new Slot(); }
    };
    private static final ClassValue<Slot> hooked = new ClassValue<>() {
        @Override protected Slot computeValue(Class<?> type) { return new Slot(); }
    };
    public static void markHooked(Class<?> resolver) { hooked.get(resolver).touched = true; }
    public static boolean isHooked(Class<?> resolver) { return hooked.get(resolver).touched; }
    public static boolean wasAdapted(Class<?> owner) { return owners.get(owner).touched; }
    public static void registerEndpoint(Class<?> adapter, Class<?> owner, MethodHandle target) {
        endpoints.get(adapter).endpoint = new Endpoint(owner, target);
    }
    public static Class<?> controllerClass(Class<?> type) {
        Endpoint endpoint = endpoints.get(type).endpoint;
        return endpoint == null ? type : endpoint.owner();
    }
    public static void replace(Class<?> owner, Class<?> type, MethodHandle factory, MethodHandle[] invokers) {
        Schema schema = type == null ? null : new Schema(owner, type, factory, invokers.clone());
        if (schema != null) schemas.get(type).schema = schema;
        Slot slot = owners.get(owner);
        slot.touched = true;
        slot.schema = schema;
    }
    public static Class<?> metadataClass(Class<?> owner) {
        Schema schema = owners.get(owner).schema;
        return schema == null ? owner : schema.type();
    }
    public static Object receiver(Object bean, Method method) throws Throwable {
        Endpoint endpoint = bean == null ? null : endpoints.get(bean.getClass()).endpoint;
        if (endpoint != null) bean = endpoint.target().invokeExact(bean);
        Schema schema = schemas.get(method.getDeclaringClass()).schema;
        if (schema == null) return bean;
        // Never unwrap a proxy and silently bypass security/transaction advice.
        if (bean == null || bean.getClass() != schema.owner())
            throw new IllegalStateException("Added exception handler requires a plain "
                    + schema.owner().getName() + " receiver; proxies and subclasses need a restart");
        return schema.factory().invokeExact(bean, schema.invokers());
    }
}
