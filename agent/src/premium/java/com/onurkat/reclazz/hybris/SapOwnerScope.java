/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Bounded native work in the exact owner tenant, with caller state restored. */
final class SapOwnerScope {
    private SapOwnerScope() { }
    static Object execute(Object context, Callable<Object> work) throws Exception {
        return inTenant(context, () -> inSession(context, work));
    }
    private static Object inTenant(Object ctx, Callable<Object> action) throws Exception {
        ClassLoader loader = loader(ctx);
        Class<?> registry = Class.forName("de.hybris.platform.core.Registry", false, loader);
        List<Object> tenants = new ArrayList<>();
        tenants.add(call(registry, "getMasterTenant"));
        tenants.addAll(map(call(registry, "getSlaveTenants")).values());
        Object owner = null;
        for (Object tenant : tenants) {
            if (tenant == null) continue;
            Object app = call(tenant, "getApplicationContext");
            for (Object c = ctx; c != null; c = call(c, "getParent")) {
                if (c != app) continue;
                require(owner == null || owner == tenant, "ambiguous-tenant");
                owner = tenant;
            }
        }
        require(owner != null, "owning-tenant-unavailable");
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        try { thread.setContextClassLoader(loader); return call(registry, "runAsTenant", owner, action); }
        finally { thread.setContextClassLoader(before); }
    }

    private static Object inSession(Object ctx, Callable<Object> action) throws Exception {
        Object service = singleton(ctx, "sessionService");
        Object before = yes(call(service, "hasCurrentSession")) ? call(service, "getCurrentSession") : null;
        Object rawBefore = before == null ? null : call(service, "getRawSession", before);
        Object fresh = null;
        try {
            fresh = call(service, "createNewSession");
            return action.call();
        } finally {
            try { if (fresh != null) call(service, "closeSession", fresh); }
            finally {
                if (rawBefore != null) call(rawBefore, "activate");
                else call(Class.forName("de.hybris.platform.jalo.JaloSession", false, loader(ctx)), "deactivate");
            }
        }
    }

    static Object singleton(Object ctx, String id) throws Exception {
        for (Object c = ctx; c != null; c = call(c, "getParent")) {
            Object bf = call(c, "getBeanFactory");
            String name = text(call(bf, "canonicalName", id));
            if (yes(call(bf, "containsSingleton", name))) return call(bf, "getSingleton", name);
            require(!yes(call(bf, "containsBeanDefinition", name)), "infrastructure-not-initialized");
        }
        throw new Refusal("infrastructure-unavailable");
    }

    private static ClassLoader loader(Object ctx) throws Exception {
        return (ClassLoader) call(call(ctx, "getBeanFactory"), "getBeanClassLoader");
    }
    private static String text(Object value) { return value == null ? null : value.toString(); }
    private static boolean yes(Object value) { return Boolean.TRUE.equals(value); }
    private static Map<?, ?> map(Object value) throws Refusal {
        require(value instanceof Map<?, ?>, "unsupported-native-map"); return (Map<?, ?>) value;
    }
    private static void require(boolean condition, String reason) throws Refusal {
        if (!condition) throw new Refusal(reason);
    }
    static final class Refusal extends Exception {
        Refusal(String reason) { super(reason); }
    }
    /** Resolve public SDK/Spring methods, plus the inspected package-private tenant context getter. */
    private static Object call(Object receiver, String name, Object... args) throws Exception {
        require(receiver != null, "missing-native-object");
        Class<?> type = receiver instanceof Class<?> c ? c : receiver.getClass();
        List<Method> methods = new ArrayList<>(Arrays.asList(type.getMethods()));
        if (name.equals("getApplicationContext") || name.equals("getGlobalListeners")) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) methods.addAll(Arrays.asList(c.getDeclaredMethods()));
        }
        for (Method method : methods) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                Class<?> parameter = params[i] == boolean.class ? Boolean.class
                        : params[i] == int.class ? Integer.class : params[i];
                if (args[i] != null && !parameter.isInstance(args[i])) match = false;
            }
            if (!match) continue;
            if (!method.trySetAccessible()) throw new Refusal("native-method-inaccessible");
            try { return method.invoke(receiver instanceof Class<?> ? null : receiver, args); }
            catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw e;
            }
        }
        throw new Refusal("native-method-unavailable");
    }

}
