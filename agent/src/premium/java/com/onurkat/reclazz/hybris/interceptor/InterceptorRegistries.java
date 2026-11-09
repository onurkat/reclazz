/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;
import java.util.*;

/** Existing owner-local ModelService caches, plus the owner's canonical context registry. */
final class InterceptorRegistries {
    private InterceptorRegistries() { }

    static Object factory(Object context) throws Exception {
        return context.getClass().getMethod("getBeanFactory").invoke(context);
    }
    static Object singleton(Object factory, String name) throws Exception {
        String canonical = (String) factory.getClass().getMethod("canonicalName", String.class).invoke(factory, name);
        return factory.getClass().getMethod("getSingleton", String.class).invoke(factory, canonical);
    }
    static String[] names(Object factory) throws Exception {
        return (String[]) factory.getClass().getMethod("getSingletonNames").invoke(factory);
    }
    static List<Object> resolve(Object context) throws Exception {
        Object factory = factory(context);
        ClassLoader loader = (ClassLoader) context.getClass().getMethod("getClassLoader").invoke(context);
        Class<?> modelType = Class.forName("de.hybris.platform.servicelayer.model.ModelService", false, loader);
        Class<?> advised = Class.forName("org.springframework.aop.framework.Advised", false, loader);
        List<Object> registries = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : names(factory)) {
            Object bean = singleton(factory, name);
            if (!modelType.isInstance(bean)) continue;
            if (advised.isInstance(bean)) {
                Object source = advised.getMethod("getTargetSource").invoke(bean);
                var isStatic = Reflect.findMethod(source.getClass(), "isStatic");
                if (isStatic == null || !Boolean.TRUE.equals(isStatic.invoke(source)))
                    throw new IllegalStateException("Dynamic ModelService target requires restart");
                bean = Objects.requireNonNull(Reflect.findMethod(source.getClass(), "getTarget")).invoke(source);
            }
            if (bean == null) throw new IllegalStateException("ModelService target unavailable");
            var getter = Reflect.findMethod(bean.getClass(), "getInterceptorRegistry");
            if (getter == null) throw new IllegalStateException("ModelService registry getter unavailable");
            Object registry = getter.invoke(bean);
            if (registry == null) throw new IllegalStateException("ModelService registry unavailable");
            if (seen.add(registry)) registries.add(registry);
        }
        Object current = singleton(factory, "interceptorRegistry");
        if (current != null && seen.add(current)) registries.add(current);
        if (registries.isEmpty()) throw new IllegalStateException("Owner interceptor registry unavailable");
        if (registries.size() > 1)
            StatusReporter.warn("ModelService and context interceptor registries differ; updating each retained registry in this owner");
        return List.copyOf(registries);
    }
}
