/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;

/** Native Security 6 pre/post managers behind their configured advisor wrappers. */
final class SpringModernSecurityAdvice {
    private static final String ROOT = "org.springframework.security.authorization.method.";
    private static final String CONFIG = "org.springframework.security.config.annotation.method.configuration.";
    private static final String WRAPPER = CONFIG + "MethodSecurityAdvisorRegistrar$AdvisorWrapper";
    private static final String DEFERRED = CONFIG + "DeferringMethodInterceptor";
    private static final String BEFORE = ROOT + "AuthorizationManagerBeforeMethodInterceptor";
    private static final String AFTER = ROOT + "AuthorizationManagerAfterMethodInterceptor";
    private static final String PRE_FILTER = ROOT + "PreFilterAuthorizationMethodInterceptor";
    private static final String POST_FILTER = ROOT + "PostFilterAuthorizationMethodInterceptor";
    private static final String RETURN_OBJECT = ROOT + "AuthorizeReturnObjectMethodInterceptor";
    private static final Set<String> TYPES = Set.of(WRAPPER, DEFERRED, BEFORE, AFTER, PRE_FILTER, POST_FILTER, RETURN_OBJECT);
    private static final ClassValue<Object> standardPointcuts = new ClassValue<>() {
        @Override protected Object computeValue(Class<?> type) {
            try {
                Object interceptor = type.getName().equals(BEFORE) ? type.getMethod("preAuthorize").invoke(null)
                        : type.getName().equals(AFTER) ? type.getMethod("postAuthorize").invoke(null)
                        : type.getConstructor().newInstance();
                return pointcut(interceptor);
            } catch (ReflectiveOperationException failure) { throw refused("standard security pointcut unavailable", failure); }
        }
    };
    private SpringModernSecurityAdvice() { }
    static boolean isAdvisor(Object advisor) { return TYPES.contains(advisor.getClass().getName()); }
    record Advice(Object interceptor, int policies, boolean inactiveOnly) { }

    static Advice inspect(Object advisor) throws ReflectiveOperationException {
        Object interceptor = unwrap(advisor);
        String type = interceptor.getClass().getName();
        int policies = type.equals(BEFORE) ? 1 : type.equals(AFTER) ? 2 : 0;
        if (!Set.of(BEFORE, AFTER, PRE_FILTER, POST_FILTER, RETURN_OBJECT).contains(type))
            throw refused("custom modern security interceptor", null);
        // Filter/return-object candidates are present by default. They can remain
        // only when they do not match; this path never executes their advice.
        if (!type.equals(RETURN_OBJECT)) {
            Object expected = standardPointcuts.get(interceptor.getClass());
            if (!expected.equals(pointcut(interceptor)) || !expected.equals(pointcut(advisor)))
                throw refused("custom modern security pointcut", null);
            Object registry = registry(interceptor, policies);
            exact(field(registry, "expressionHandler"), "org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler");
            if (policies != 0) {
                exact(field(registry, "defaultHandler"), ROOT + "ThrowingMethodAuthorizationDeniedHandler");
                exact(field(interceptor, "defaultHandler"), ROOT + "ThrowingMethodAuthorizationDeniedHandler");
            }
        } else {
            exact(field(interceptor, "authorizationProxyFactory"), ROOT + "AuthorizationAdvisorProxyFactory");
        }
        return new Advice(advice(advisor), policies, policies == 0);
    }

    static int requiredPolicies(Method metadata) {
        int flags = 0;
        for (var element : List.of(metadata, metadata.getDeclaringClass())) {
            for (var annotation : element.getDeclaredAnnotations()) {
                String type = annotation.annotationType().getName();
                if (type.equals("org.springframework.security.access.prepost.PreAuthorize")) flags |= 1;
                if (type.equals("org.springframework.security.access.prepost.PostAuthorize")) flags |= 2;
            }
        }
        return flags;
    }

    static void forget(Object bean, List<Method> methods) throws ReflectiveOperationException {
        if (!isAdvisor(bean) || methods.isEmpty()) return;
        Object interceptor = unwrap(bean);
        int flags = interceptor.getClass().getName().equals(BEFORE) ? 1
                : interceptor.getClass().getName().equals(AFTER) ? 2 : 0;
        if (flags == 0) return;
        Object manager = manager(interceptor);
        if (!manager.getClass().getName().equals(ROOT + (flags == 1 ? "Pre" : "Post") + "AuthorizeAuthorizationManager")) return;
        Object registry = field(manager, "registry");
        Object cache = field(registry, "cachedAttributes");
        if (!(cache instanceof java.util.concurrent.ConcurrentMap<?, ?> map))
            throw refused("modern security attribute cache unavailable", null);
        // Native registry uses ConcurrentHashMap<MethodClassKey,...>. Do not clear
        // unrelated original methods or other owners' entries.
        for (Object key : map.keySet()) if (methods.contains(field(key, "method"))) map.remove(key);
    }
    private static Object registry(Object interceptor, int policies) throws ReflectiveOperationException {
        Object source = interceptor;
        String prefix;
        if (policies != 0) {
            prefix = policies == 1 ? "PreAuthorize" : "PostAuthorize";
            source = manager(interceptor);
            exact(source, ROOT + prefix + "AuthorizationManager");
        } else prefix = interceptor.getClass().getName().equals(PRE_FILTER) ? "PreFilter" : "PostFilter";
        Object registry = field(source, "registry");
        exact(registry, ROOT + prefix + "ExpressionAttributeRegistry");
        return registry;
    }
    private static Object manager(Object interceptor) throws ReflectiveOperationException {
        Object manager = field(interceptor, "authorizationManager");
        if (manager.getClass().getName().equals("org.springframework.security.authorization.ObservationAuthorizationManager")) {
            Object delegate = field(manager, "delegate");
            if (field(manager, "handler") != delegate) throw refused("custom observed denial handler", null);
            manager = delegate;
        }
        return manager;
    }
    private static Object unwrap(Object advisor) throws ReflectiveOperationException {
        Object current = advisor;
        if (current.getClass().getName().equals(WRAPPER)) current = field(current, "advisor");
        if (current.getClass().getName().equals(DEFERRED)) {
            Object supplier = field(current, "delegate");
            exact(supplier, "org.springframework.util.function.SingletonSupplier");
            current = ((Supplier<?>) supplier).get();
        }
        return current;
    }
    private static Object advice(Object advisor) throws ReflectiveOperationException {
        return Class.forName("org.springframework.aop.Advisor", false, advisor.getClass().getClassLoader())
                .getMethod("getAdvice").invoke(advisor);
    }
    private static Object pointcut(Object advisor) throws ReflectiveOperationException {
        return Class.forName("org.springframework.aop.PointcutAdvisor", false, advisor.getClass().getClassLoader())
                .getMethod("getPointcut").invoke(advisor);
    }
    private static Object field(Object bean, String name) throws ReflectiveOperationException {
        var field = Reflect.findField(bean.getClass(), name);
        if (field == null) throw refused("modern security metadata unavailable: " + name, null);
        return field.get(bean);
    }
    private static void exact(Object value, String name) {
        if (value == null || !value.getClass().getName().equals(name)) throw refused("expected " + name, null);
    }
    private static IllegalStateException refused(String reason, Throwable cause) {
        return new IllegalStateException("Added Spring operation refused: " + reason, cause);
    }
}
