/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.MvcSingletonBridge;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Named local MVC singleton identities only; never a general object-graph traversal. */
final class SpringMvcSingletons {
    private record Entry(Object context, String name, Object original, Class<?> framework, int hook) { }
    private final List<Entry> entries = new ArrayList<>();

    static SpringMvcSingletons capture(List<Object> contexts) {
        var snapshot = new SpringMvcSingletons();
        for (Object context : contexts) {
            try {
                Object factory = SpringBeans.getBeanFactory(context);
                if (factory == null) continue;
                Method names = Reflect.findMethod(factory.getClass(), "getSingletonNames");
                Method singleton = Reflect.findMethod(factory.getClass(), "getSingleton", String.class);
                if (names == null || singleton == null) continue;
                for (String name : (String[]) names.invoke(factory)) {
                    Object bean = singleton.invoke(factory, name);
                    if (bean == null || Proxy.isProxyClass(bean.getClass()) || bean.getClass().getName().contains("$$")) continue;
                    if (!(Boolean) factory.getClass().getMethod("containsBeanDefinition", String.class).invoke(factory, name)) continue;
                    if ((Boolean) factory.getClass().getMethod("isFactoryBean", String.class).invoke(factory, name)) continue;
                    for (String type : List.of("org.springframework.web.servlet.HandlerInterceptor", "org.springframework.web.servlet.HandlerMapping")) {
                        Class<?> framework;
                        try { framework = Class.forName(type, false, bean.getClass().getClassLoader()); }
                        catch (ClassNotFoundException absent) { continue; }
                        if (framework.isInstance(bean)) {
                            snapshot.entries.add(new Entry(context, name, bean, framework, type.endsWith("HandlerInterceptor") ? 1 : 2));
                            break;
                        }
                    }
                }
            } catch (Exception failure) {
                StatusReporter.warn("MVC singleton snapshot unavailable: " + com.onurkat.reclazz.ui.Failures.describe(failure));
            }
        }
        // Recreate missing interceptors before mappings, whose native initialization may need them.
        snapshot.entries.sort(java.util.Comparator.comparingInt(Entry::hook));
        return snapshot;
    }

    void publishCurrent() {
        var pairs = new IdentityHashMap<Object, Object>();
        for (Entry entry : entries) {
            try {
                if (!(Boolean) entry.context().getClass().getMethod("isActive").invoke(entry.context())) continue;
                Object factory = SpringBeans.getBeanFactory(entry.context());
                if (!(Boolean) factory.getClass().getMethod("containsBeanDefinition", String.class).invoke(factory, entry.name())) continue;
                if ((Boolean) factory.getClass().getMethod("isFactoryBean", String.class).invoke(factory, entry.name())) continue;
                if (!(Boolean) factory.getClass().getMethod("isSingleton", String.class).invoke(factory, entry.name())) continue;
                Object fresh = entry.context().getClass().getMethod("getBean", String.class).invoke(entry.context(), entry.name());
                if (fresh == null || fresh == entry.original()) continue;
                if (fresh.getClass() != entry.original().getClass() || !MvcSingletonBridge.available(entry.framework().getClassLoader(), entry.hook())) {
                    StatusReporter.warn("MVC singleton routing unavailable for bean '" + entry.name() + "'; restart required");
                    com.onurkat.reclazz.ui.RestartLedger.note(entry.original().getClass().getName(),
                            "MVC singleton routing unavailable for bean '" + entry.name() + "'; restart required");
                    continue;
                }
                pairs.put(entry.original(), fresh);
            } catch (Exception failure) {
                StatusReporter.warn("MVC singleton refresh unavailable: " + com.onurkat.reclazz.ui.Failures.describe(failure));
            }
        }
        MvcSingletonBridge.publish(pairs);
    }
}
