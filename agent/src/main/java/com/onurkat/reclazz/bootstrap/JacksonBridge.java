/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Jackson-only metadata and invocation routing. Bootstrap class: JDK dependencies only. */
public final class JacksonBridge {
    private JacksonBridge() { }

    private static final class Members { volatile Class<?> schema; }
    private static final class Route { volatile Schema schema; }
    private record Schema(Class<?> owner, Map<Method, MethodHandle> targets) { }
    private static final ClassValue<Members> members = new ClassValue<>() {
        @Override protected Members computeValue(Class<?> type) { return new Members(); }
    };
    private static final ClassValue<Route> routes = new ClassValue<>() {
        @Override protected Route computeValue(Class<?> type) { return new Route(); }
    };

    /** Whether this owner currently needs the Jackson metadata adapter. */
    public static boolean hasGetters(Class<?> owner) {
        return members.get(owner).schema != null;
    }

    /** Publish routes before making their metadata discoverable. */
    public static void replace(Class<?> owner, Class<?> schema, Map<Method, MethodHandle> targets) {
        Map<Method, MethodHandle> snapshot = Map.copyOf(targets);
        if (schema != null) routes.get(schema).schema = new Schema(owner, snapshot);
        members.get(owner).schema = targets.isEmpty() ? null : schema;
    }

    /** Called only from Jackson, never from an application's ordinary reflection. */
    public static Method[] getDeclaredMethods(Class<?> owner) {
        Method[] original = ReflectionBridge.getDeclaredMethods(owner);
        Class<?> schema = members.get(owner).schema;
        if (schema == null) return original;
        // Fresh Method copies keep one mapper's setAccessible(true) from
        // granting access to another mapper with access overriding disabled.
        Method[] added = schema.getDeclaredMethods();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Method method : added) keys.add(key(method));
        List<Method> merged = new java.util.ArrayList<>();
        for (Method method : original) if (!keys.contains(key(method))) merged.add(method);
        merged.addAll(Arrays.asList(added));
        return merged.toArray(Method[]::new);
    }

    private static String key(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes());
    }

    /** Jackson's declaring-class checks must see the DTO, not its metadata carrier. */
    public static Class<?> getDeclaringClass(Method method) {
        Schema schema = routes.get(method.getDeclaringClass()).schema;
        return schema == null ? method.getDeclaringClass() : schema.owner();
    }

    /** Preserve Method.invoke's exception boundary, including for a throwing added getter. */
    @SuppressWarnings("deprecation")
    public static Object invoke(Method method, Object receiver, Object[] arguments)
            throws IllegalAccessException, InvocationTargetException {
        Schema schema = routes.get(method.getDeclaringClass()).schema;
        if (schema == null) return method.invoke(receiver, arguments);
        MethodHandle target = schema.targets().get(method);
        if (target == null) throw new IllegalArgumentException("Unknown Jackson getter " + method.getName());
        if (receiver == null) throw new NullPointerException("getter receiver");
        if (!schema.owner().isInstance(receiver) || (arguments != null && arguments.length != 0))
            throw new IllegalArgumentException("Invalid receiver or arguments for " + method.getName());
        if (!method.isAccessible() && (!Modifier.isPublic(method.getModifiers())
                || !Modifier.isPublic(schema.owner().getModifiers())))
            throw new IllegalAccessException("Getter access was not enabled by Jackson: " + method.getName());
        try {
            return target.invoke(receiver);
        } catch (Throwable failure) {
            throw new InvocationTargetException(failure);
        }
    }
}
