/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Spring's {@code ReflectionUtils}, as seen by the application code that calls it.
 *
 * <p>Spring's own classes get the physical member view on purpose: its
 * lifecycle scans would otherwise register an added {@code @Async} or
 * {@code @EventListener} method next to the adapter that already owns it. But
 * {@code ReflectionUtils} is also a utility that application code calls in
 * place of {@code getDeclaredMethods}, and then the view belongs to the caller.
 * SAP Commerce finds a model's getter and setter with
 * {@code ReflectionUtils.doWithMethods}; with the physical view an attribute
 * added to a loaded model had no accessor, and
 * {@code ModelService.setAttributeValue} failed for it.
 *
 * <p>Call sites outside {@code org.springframework} are rewritten to come here.
 * Spring's method runs unchanged first, so everything it finds is found in the
 * same order and through the same caches; the methods a reload added are then
 * offered to the same callback and filter, over the same hierarchy. A class
 * nothing was added to costs one extra hop.
 */
public final class SpringReflectionView {

    private SpringReflectionView() {
    }

    /** {@code doWithMethods(Class, MethodCallback)} is the filtered form with no filter, in Spring 5 and 6. */
    public static void doWithMethods(Class<?> clazz, Object callback, Class<?> utils) throws Throwable {
        doWithMethods(clazz, callback, null, utils);
    }

    public static void doWithMethods(Class<?> clazz, Object callback, Object filter, Class<?> utils) throws Throwable {
        Handles h = HANDLES.get(utils);
        h.doWithMethods.invoke(clazz, callback, filter);
        offerHierarchy(h, clazz, callback, filter);
    }

    public static void doWithLocalMethods(Class<?> clazz, Object callback, Class<?> utils) throws Throwable {
        Handles h = HANDLES.get(utils);
        h.doWithLocalMethods.invoke(clazz, callback);
        for (Method added : ReflectionBridge.addedMethods(clazz)) offer(h, added, callback);
    }

    /** {@code findMethod(Class, String)} asks for the no-argument method, as Spring's own overload does. */
    public static Method findMethod(Class<?> clazz, String name, Class<?> utils) throws Throwable {
        return findMethod(clazz, name, new Class<?>[0], utils);
    }

    public static Method findMethod(Class<?> clazz, String name, Class<?>[] parameterTypes, Class<?> utils)
            throws Throwable {
        Method found = (Method) HANDLES.get(utils).findMethod.invoke(clazz, name, parameterTypes);
        if (found != null) return found;
        for (Class<?> type = clazz; type != null; type = type.getSuperclass()) {
            for (Method added : ReflectionBridge.addedMethods(type)) {
                if (added.getName().equals(name)
                        && (parameterTypes == null || Arrays.equals(parameterTypes, added.getParameterTypes()))) {
                    return added;
                }
            }
        }
        return null;
    }

    /** The same walk Spring makes: up the superclasses, or across the super-interfaces of an interface. */
    private static void offerHierarchy(Handles h, Class<?> clazz, Object callback, Object filter) throws Throwable {
        for (Method added : ReflectionBridge.addedMethods(clazz)) {
            if (filter == null || (boolean) h.matches.invoke(filter, added)) offer(h, added, callback);
        }
        if (clazz.getSuperclass() != null) {
            offerHierarchy(h, clazz.getSuperclass(), callback, filter);
        } else if (clazz.isInterface()) {
            for (Class<?> face : clazz.getInterfaces()) offerHierarchy(h, face, callback, filter);
        }
    }

    private static void offer(Handles h, Method added, Object callback) throws Throwable {
        try {
            h.doWith.invoke(callback, added);
        } catch (IllegalAccessException ex) {
            // What Spring's own loop turns the same failure into.
            throw new IllegalStateException("Not allowed to access method '" + added.getName() + "': " + ex);
        }
    }

    /** Resolved once per ReflectionUtils class, which is once per Spring copy in the JVM. */
    private static final ClassValue<Handles> HANDLES = new ClassValue<>() {
        @Override
        protected Handles computeValue(Class<?> utils) {
            try {
                return new Handles(utils);
            } catch (ReflectiveOperationException unexpected) {
                throw new IllegalStateException("Spring ReflectionUtils has an unexpected shape", unexpected);
            }
        }
    };

    private static final class Handles {
        final MethodHandle doWithMethods;
        final MethodHandle doWithLocalMethods;
        final MethodHandle findMethod;
        final MethodHandle doWith;
        final MethodHandle matches;

        Handles(Class<?> utils) throws ReflectiveOperationException {
            ClassLoader loader = utils.getClassLoader();
            Class<?> callback = Class.forName(utils.getName() + "$MethodCallback", false, loader);
            Class<?> filter = Class.forName(utils.getName() + "$MethodFilter", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            doWithMethods = lookup.findStatic(utils, "doWithMethods",
                            MethodType.methodType(void.class, Class.class, callback, filter))
                    .asType(MethodType.methodType(void.class, Class.class, Object.class, Object.class));
            doWithLocalMethods = lookup.findStatic(utils, "doWithLocalMethods",
                            MethodType.methodType(void.class, Class.class, callback))
                    .asType(MethodType.methodType(void.class, Class.class, Object.class));
            findMethod = lookup.findStatic(utils, "findMethod",
                    MethodType.methodType(Method.class, Class.class, String.class, Class[].class));
            doWith = lookup.findVirtual(callback, "doWith", MethodType.methodType(void.class, Method.class))
                    .asType(MethodType.methodType(void.class, Object.class, Method.class));
            matches = lookup.findVirtual(filter, "matches", MethodType.methodType(boolean.class, Method.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, Method.class));
        }
    }
}
