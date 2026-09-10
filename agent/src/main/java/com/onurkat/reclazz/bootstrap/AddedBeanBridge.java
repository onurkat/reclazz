/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.*;
import java.util.Arrays;
import java.util.Map;

/** JDK-only bean-reference boundary, including calls from within a configuration. */
public final class AddedBeanBridge {
    @FunctionalInterface
    public interface Invocation {
        Object invoke(Object receiver, Object[] arguments, MethodHandle direct) throws Throwable;
    }
    private static final class Routes { volatile Map<String, Invocation> methods = Map.of(); }
    private static final ClassValue<Routes> routes = new ClassValue<>() {
        @Override protected Routes computeValue(Class<?> type) { return new Routes(); }
    };
    private static final MethodHandle INVOKE;
    private static final MethodHandle HAS_ROUTES;
    static {
        try {
            INVOKE = MethodHandles.lookup().findStatic(AddedBeanBridge.class, "invoke",
                    MethodType.methodType(Object.class, Routes.class, String.class, MethodHandle.class, Object[].class));
            HAS_ROUTES = MethodHandles.lookup().findStatic(AddedBeanBridge.class, "hasRoutes",
                    MethodType.methodType(boolean.class, Routes.class));
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private AddedBeanBridge() { }

    public static void publish(Class<?> owner, Map<String, Invocation> methods) {
        routes.get(owner).methods = Map.copyOf(methods);
    }
    public static CallSite call(Class<?> owner, String key, CallSite direct) {
        MethodType type = direct.type();
        Routes ownerRoutes = routes.get(owner);
        MethodHandle raw = direct.dynamicInvoker();
        MethodHandle call = MethodHandles.insertArguments(INVOKE, 0, ownerRoutes, key, raw);
        MethodHandle guarded = MethodHandles.guardWithTest(HAS_ROUTES.bindTo(ownerRoutes),
                call.asCollector(Object[].class, type.parameterCount()).asType(type), raw);
        return new ConstantCallSite(guarded);
    }
    private static boolean hasRoutes(Routes routes) {
        // Read the published map itself, avoiding a separately published flag.
        // Plain owners keep their exact invoker: no argument array or boxing.
        return !routes.methods.isEmpty();
    }
    private static Object invoke(Routes routes, String key, MethodHandle direct, Object[] all) throws Throwable {
        Invocation operation = routes.methods.get(key);
        if (operation == null) return direct.invokeWithArguments(all);
        return operation.invoke(all[0], Arrays.copyOfRange(all, 1, all.length), direct);
    }
}
