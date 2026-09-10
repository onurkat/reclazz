/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.AccessibleObject;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Jackson-only metadata and invocation routing. Bootstrap class: JDK dependencies only. */
public final class JacksonBridge {
    private JacksonBridge() { }

    private static final class Members { volatile Class<?> schema; }
    private static final class Route { volatile Schema schema; }
    public record FieldAccess(MethodHandle getter, MethodHandle setter) { }
    private record Schema(Class<?> owner, Map<Method, MethodHandle> targets, Map<Field, FieldAccess> fields) { }
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
        replace(owner, schema, targets, Map.of());
    }

    public static void replace(Class<?> owner, Class<?> schema, Map<Method, MethodHandle> targets,
                               Map<Field, FieldAccess> fields) {
        if (schema != null) routes.get(schema).schema = new Schema(owner, Map.copyOf(targets), Map.copyOf(fields));
        members.get(owner).schema = targets.isEmpty() && fields.isEmpty() ? null : schema;
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

    /** Field metadata is visible only to Jackson's ordinary reflective path. */
    public static Field[] getDeclaredFields(Class<?> owner) {
        Field[] original = ReflectionBridge.getDeclaredFields(owner);
        Class<?> schema = members.get(owner).schema;
        if (schema == null) return original;
        Field[] added = schema.getDeclaredFields();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Field field : added) names.add(field.getName());
        List<Field> merged = new java.util.ArrayList<>();
        for (Field field : original) if (!names.contains(field.getName())) merged.add(field);
        merged.addAll(Arrays.asList(added));
        return merged.toArray(Field[]::new);
    }

    /** Jackson's declaring-class checks must see the DTO, not its metadata carrier. */
    public static Class<?> getDeclaringClass(Method method) {
        return getDeclaringClass((Member) method);
    }

    public static Class<?> getDeclaringClass(Field field) {
        return getDeclaringClass((Member) field);
    }

    public static Class<?> getDeclaringClass(Member member) {
        Schema schema = routes.get(member.getDeclaringClass()).schema;
        return schema == null ? member.getDeclaringClass() : schema.owner();
    }

    /** Preserve Method.invoke's exception boundary, including for a throwing added getter. */
    @SuppressWarnings("deprecation")
    public static Object invoke(Method method, Object receiver, Object[] arguments)
            throws IllegalAccessException, InvocationTargetException {
        Schema schema = routes.get(method.getDeclaringClass()).schema;
        if (schema == null) return method.invoke(receiver, arguments);
        MethodHandle target = schema.targets().get(method);
        if (target == null) throw new IllegalArgumentException("Unknown Jackson accessor " + method.getName());
        checkAccess(method, method, schema.owner(), receiver);
        int count = arguments == null ? 0 : arguments.length;
        if (count != method.getParameterCount())
            throw new IllegalArgumentException("Invalid receiver or arguments for " + method.getName());
        Class<?>[] types = method.getParameterTypes();
        if (count > 1) throw new IllegalArgumentException("Unsupported Jackson accessor arity: " + method.getName());
        Object value = count == 0 ? null : checkedValue(types[0], arguments[0]);
        try {
            return count == 0 ? target.invoke(receiver) : target.invoke(receiver, value);
        } catch (Throwable failure) {
            throw new InvocationTargetException(failure);
        }
    }

    public static Object get(Field field, Object receiver) throws IllegalAccessException {
        Schema schema = routes.get(field.getDeclaringClass()).schema;
        if (schema == null) return field.get(receiver);
        checkAccess(field, field, schema.owner(), receiver);
        FieldAccess access = schema.fields().get(field);
        if (access == null) throw new IllegalArgumentException("Unknown Jackson field " + field.getName());
        try { return access.getter().invoke(receiver); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot read added field " + field.getName(), failure); }
    }

    public static void set(Field field, Object receiver, Object value) throws IllegalAccessException {
        Schema schema = routes.get(field.getDeclaringClass()).schema;
        if (schema == null) { field.set(receiver, value); return; }
        checkAccess(field, field, schema.owner(), receiver);
        FieldAccess access = schema.fields().get(field);
        if (access == null) throw new IllegalArgumentException("Unknown Jackson field " + field.getName());
        // Final fields in hidden metadata classes cannot be written by reflection.
        // Do not bypass that restriction through the external field store.
        if (access.setter() == null) throw new IllegalAccessException("Cannot write final added field " + field.getName());
        Object converted = checkedValue(field.getType(), value);
        try { access.setter().invoke(receiver, converted); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot write added field " + field.getName(), failure); }
    }

    @SuppressWarnings("deprecation")
    private static void checkAccess(Member member, AccessibleObject accessible, Class<?> owner, Object receiver)
            throws IllegalAccessException {
        if (receiver == null) throw new NullPointerException("accessor receiver");
        if (!owner.isInstance(receiver)) throw new IllegalArgumentException("Invalid receiver for " + member.getName());
        if (!accessible.isAccessible() && (!Modifier.isPublic(member.getModifiers()) || !Modifier.isPublic(owner.getModifiers())))
            throw new IllegalAccessException("Accessor access was not enabled by Jackson: " + member.getName());
    }

    /** Reflection permits unboxing and primitive widening, never narrowing. */
    private static Object checkedValue(Class<?> type, Object value) {
        if (!type.isPrimitive()) {
            if (value == null || type.isInstance(value)) return value;
        } else if (type == boolean.class && value instanceof Boolean) return value;
        else if (type == char.class && value instanceof Character) return value;
        else {
            boolean allowed = value instanceof Byte && type != boolean.class && type != char.class
                    || value instanceof Short && (type == short.class || type == int.class || type == long.class || type == float.class || type == double.class)
                    || (value instanceof Integer || value instanceof Character) && (type == int.class || type == long.class || type == float.class || type == double.class)
                    || value instanceof Long && (type == long.class || type == float.class || type == double.class)
                    || value instanceof Float && (type == float.class || type == double.class)
                    || value instanceof Double && type == double.class;
            if (allowed) {
                Number number = value instanceof Character c ? Integer.valueOf(c) : (Number) value;
                if (type == byte.class) return number.byteValue();
                if (type == short.class) return number.shortValue();
                if (type == int.class) return number.intValue();
                if (type == long.class) return number.longValue();
                if (type == float.class) return number.floatValue();
                if (type == double.class) return number.doubleValue();
            }
        }
        throw new IllegalArgumentException("Cannot assign " + (value == null ? "null" : value.getClass().getName()) + " to " + type.getName());
    }
}
