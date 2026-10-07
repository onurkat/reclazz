/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import java.beans.PropertyDescriptor;
import java.lang.reflect.*;
import java.math.*;
import java.net.URI;
import java.util.*;

/** Bounded physical-property binding; never delegates a property path or invokes a getter. */
final class XmlNativePropertyBinding {
    private XmlNativePropertyBinding() { }

    record Binding(Method setter, Object parameter, Object shape, Object converter,
                   ClassLoader springLoader, boolean legacy, boolean reference) {
        Object convert(Object value) throws Exception {
            Class<?> target = resolve(shape);
            require(target != null, "unresolved physical property type requires restart");
            if (reference && boxed(target).isInstance(value)) return value; // Keep an existing whole-bean reference, even a custom collection.
            if (legacy) {
                Object result = SpringXmlReloader.coerce(value, setter.getParameterTypes()[0]);
                require(assignable(shape, result, 0), "unsupported private/proxy property conversion");
                return result;
            }
            if (converter != null) {
                // A custom/lazy collection carrier may execute application code when iterated.
                require(plainCarrier(value), "unverified collection carrier requires restart");
                Class<?> contract = Class.forName("org.springframework.beans.TypeConverter", false, springLoader);
                Class<?> parameterType = Class.forName("org.springframework.core.MethodParameter", false, springLoader);
                Object result = contract.getMethod("convertIfNecessary", Object.class, Class.class, parameterType)
                        .invoke(converter, value, target, parameter);
                require(assignable(shape, result, 0), "converted property value does not match the declared type");
                return result;
            }
            require(assignable(shape, value, 0), "unsupported custom/array/unresolved property conversion");
            return value; // Compatible custom objects keep existing assignment, without new object conversion.
        }
    }

    static Binding prepare(Object factory, Object bean, String property, Object raw) throws Exception {
        require(simpleName(property), "only simple physical XML property names are supported");
        ClassLoader spring = factory.getClass().getClassLoader();
        Method setter = null;
        try {
            Class<?> wrapper = Class.forName("org.springframework.beans.BeanWrapperImpl", false, spring);
            Object metadata = wrapper.getConstructor(Object.class).newInstance(bean);
            PropertyDescriptor descriptor = (PropertyDescriptor) wrapper.getMethod("getPropertyDescriptor", String.class).invoke(metadata, property);
            setter = descriptor.getWriteMethod();
        } catch (InvocationTargetException unavailableProperty) {
            // An absent/non-public physical property retains the bounded legacy fallback.
            // Never infer a type by executing an application getter.
        }
        if (setter == null) setter = privateSetter(bean.getClass(), property);
        require(setter != null && setter.getParameterCount() == 1, "no physical setter for property '" + property + "'");
        setter.setAccessible(true);
        Class<?> parameterType = Class.forName("org.springframework.core.MethodParameter", false, spring);
        Object parameter = parameterType.getConstructor(Method.class, int.class).newInstance(setter, 0);
        parameter = parameterType.getMethod("withContainingClass", Class.class).invoke(parameter, bean.getClass());
        Class<?> resolvable = Class.forName("org.springframework.core.ResolvableType", false, spring);
        Object shape = resolvable.getMethod("forMethodParameter", parameterType).invoke(null, parameter);
        require(!resourceShape(shape, spring, 0), "resource/path/URL XML target conversion requires restart");
        ClassLoader application = (ClassLoader) factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
        rawTypes(raw, application, 0);
        boolean legacy = !Modifier.isPublic(setter.getModifiers()) || isProxy(bean, spring);
        boolean reference = raw != null && raw.getClass().getName().equals("org.springframework.beans.factory.config.RuntimeBeanReference");
        Object converter = null;
        if (!legacy && !reference && nativeShape(shape, 0)) {
            converter = factory.getClass().getMethod("getTypeConverter").invoke(factory);
            require(converter != null && converter.getClass() == Class.forName("org.springframework.beans.SimpleTypeConverter", false, spring),
                    "unverified custom TypeConverter requires restart");
        }
        return new Binding(setter, parameter, shape, converter, spring, legacy, reference);
    }

    private static Method privateSetter(Class<?> type, String property) {
        String name = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Method found = null;
            for (Method method : c.getDeclaredMethods()) if (method.getName().equals(name) && method.getParameterCount() == 1) {
                if (found != null) throw new IllegalArgumentException("ambiguous non-public physical setter");
                found = method;
            }
            if (found != null && !Modifier.isPublic(found.getModifiers())) return found;
        }
        return null;
    }
    private static boolean isProxy(Object bean, ClassLoader spring) throws ClassNotFoundException {
        if (Proxy.isProxyClass(bean.getClass())) return true;
        try { return Class.forName("org.springframework.aop.SpringProxy", false, spring).isInstance(bean); }
        catch (ClassNotFoundException noAop) { return false; }
    }
    private static boolean simpleName(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int n = 1; n < name.length(); n++) if (!Character.isJavaIdentifierPart(name.charAt(n))) return false;
        return true;
    }
    private static Class<?> resolve(Object shape) throws Exception { return (Class<?>) shape.getClass().getMethod("resolve").invoke(shape); }
    private static Object generic(Object shape, int index) throws Exception { return shape.getClass().getMethod("getGeneric", int[].class).invoke(shape, (Object) new int[]{index}); }
    private static Object component(Object shape) throws Exception { return shape.getClass().getMethod("getComponentType").invoke(shape); }
    private static boolean scalar(Class<?> type) {
        return type != null && (type.isPrimitive() || Set.of(String.class, Boolean.class, Byte.class, Short.class, Integer.class,
                Long.class, Float.class, Double.class, Character.class, Class.class, BigDecimal.class, BigInteger.class, URI.class).contains(type) || type.isEnum());
    }
    private static boolean container(Class<?> type) { return type != null && (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)); }
    private static boolean nativeShape(Object shape, int depth) throws Exception {
        if (depth > 8) return false;
        Class<?> type = resolve(shape);
        if (scalar(type)) return true;
        if (type == null || !Set.of(List.class, Set.class, Collection.class, Map.class, ArrayList.class, LinkedList.class,
                HashSet.class, LinkedHashSet.class, HashMap.class, LinkedHashMap.class, TreeMap.class, TreeSet.class).contains(type)) return false;
        return nativeShape(generic(shape, 0), depth + 1) && (!Map.class.isAssignableFrom(type) || nativeShape(generic(shape, 1), depth + 1));
    }
    private static boolean resourceShape(Object shape, ClassLoader spring, int depth) throws Exception {
        require(depth <= 8, "property type nesting exceeds verification bound");
        Class<?> type = resolve(shape);
        if (type == null) return false;
        if (java.io.InputStream.class.isAssignableFrom(type) || java.io.OutputStream.class.isAssignableFrom(type)
                || java.io.Reader.class.isAssignableFrom(type) || java.io.Writer.class.isAssignableFrom(type)
                || java.io.File.class.isAssignableFrom(type) || java.nio.file.Path.class.isAssignableFrom(type)
                || java.net.URL.class.isAssignableFrom(type)
                || Class.forName("org.springframework.core.io.Resource", false, spring).isAssignableFrom(type)) return true;
        if (type.isArray()) return resourceShape(component(shape), spring, depth + 1);
        if (!container(type)) return false;
        return resourceShape(generic(shape, 0), spring, depth + 1) || (Map.class.isAssignableFrom(type) && resourceShape(generic(shape, 1), spring, depth + 1));
    }
    private static boolean assignable(Object shape, Object value, int depth) throws Exception {
        if (depth > 8) return false;
        Class<?> type = resolve(shape);
        // Unresolved container elements retain native raw/wildcard assignment;
        // nativeShape excludes them from the new conversion route.
        if (type == null) return depth > 0;
        if (value == null) return !type.isPrimitive();
        if (!boxed(type).isInstance(value)) return false;
        if (type.isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) if (!assignable(component(shape), Array.get(value, i), depth + 1)) return false;
        } else if (value instanceof Collection<?> values && Collection.class.isAssignableFrom(type)) {
            if (!plainCarrier(value)) return false;
            for (Object item : values) if (!assignable(generic(shape, 0), item, depth + 1)) return false;
        } else if (value instanceof Map<?,?> values && Map.class.isAssignableFrom(type)) {
            if (!plainCarrier(value)) return false;
            for (var item : values.entrySet()) if (!assignable(generic(shape, 0), item.getKey(), depth + 1) || !assignable(generic(shape, 1), item.getValue(), depth + 1)) return false;
        }
        return true;
    }
    private static boolean plainCarrier(Object value) {
        return !(value instanceof Collection<?> || value instanceof Map<?,?>) || value.getClass().getClassLoader() == null;
    }
    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        return switch(type.getName()) { case "boolean" -> Boolean.class; case "byte" -> Byte.class; case "short" -> Short.class;
            case "int" -> Integer.class; case "long" -> Long.class; case "float" -> Float.class; case "double" -> Double.class; case "char" -> Character.class; default -> type; };
    }
    /** Explicit XML type conversions occur inside resolveValue; verify them BEFORE entering it. */
    private static void rawTypes(Object raw, ClassLoader application, int depth) throws Exception {
        require(depth <= 32, "XML value nesting exceeds verification bound");
        if (raw == null) return;
        String name = raw.getClass().getName();
        if (name.equals("org.springframework.beans.factory.config.TypedStringValue")) {
            String target = (String) raw.getClass().getMethod("getTargetTypeName").invoke(raw);
            if (target != null) explicitType(target, application);
        } else if (raw instanceof Collection<?> values) {
            if (Set.of("org.springframework.beans.factory.support.ManagedList", "org.springframework.beans.factory.support.ManagedSet",
                    "org.springframework.beans.factory.support.ManagedArray").contains(name)) {
                String element = (String) raw.getClass().getMethod("getElementTypeName").invoke(raw);
                if (element != null && !element.isEmpty()) explicitType(element, application);
            }
            for (Object v : values) rawTypes(v, application, depth + 1);
        } else if (raw instanceof Map<?,?> values) {
            if (name.equals("org.springframework.beans.factory.support.ManagedMap")) {
                for (String method : new String[]{"getKeyTypeName", "getValueTypeName"}) {
                    String target = (String) raw.getClass().getMethod(method).invoke(raw);
                    if (target != null && !target.isEmpty()) explicitType(target, application);
                }
            }
            for (var v : values.entrySet()) { rawTypes(v.getKey(), application, depth + 1); rawTypes(v.getValue(), application, depth + 1); }
        } else if (name.equals("org.springframework.beans.factory.config.BeanDefinitionHolder")) {
            rawTypes(raw.getClass().getMethod("getBeanDefinition").invoke(raw), application, depth + 1);
        } else if (Set.of("org.springframework.beans.factory.support.GenericBeanDefinition", "org.springframework.beans.factory.support.RootBeanDefinition",
                "org.springframework.beans.factory.support.ChildBeanDefinition").contains(name)) {
            for (Object pv : SpringReflection.getPropertyValueArray(SpringReflection.getPropertyValues(raw)))
                rawTypes(SpringReflection.getPropertyValue(pv), application, depth + 1);
            Object args = raw.getClass().getMethod("getConstructorArgumentValues").invoke(raw);
            for (Object v : ((Map<?,?>) args.getClass().getMethod("getIndexedArgumentValues").invoke(args)).values())
                rawTypes(v.getClass().getMethod("getValue").invoke(v), application, depth + 1);
            for (Object v : (List<?>) args.getClass().getMethod("getGenericArgumentValues").invoke(args))
                rawTypes(v.getClass().getMethod("getValue").invoke(v), application, depth + 1);
        }
    }
    private static void explicitType(String name, ClassLoader application) throws Exception {
        // Native ClassUtils also understands primitive names and Java array notation.
        Class<?> type = (Class<?>) Class.forName("org.springframework.util.ClassUtils", false, application)
                .getMethod("forName", String.class, ClassLoader.class).invoke(null, name, application);
        while (type.isArray()) type = type.getComponentType();
        require(scalar(type), "unsupported explicit XML type conversion requires restart");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
