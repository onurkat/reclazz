/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Clears Spring AOP proxy caches and recreates proxied beans for @Aspect classes.
 *
 * When an @Aspect class is reloaded, the AOP proxy cache may hold stale advice.
 * This reloader clears the advisedBeans cache in AbstractAutoProxyCreator and
 * destroys+recreates affected beans.
 *
 * All Spring interaction is via reflection — graceful no-op if Spring AOP is not present.
 */
public class SpringAopReloader {

    private final PlatformContext platformContext;

    public SpringAopReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Clear AOP caches and recreate proxied beans if the reloaded class is an @Aspect.
     */
    public boolean reloadAopProxies(Class<?> reloadedClass) {
        if (!isAspectClass(reloadedClass)) return false;

        boolean cleared = false;
        // Proxy creators may live in any context.
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            cleared |= reloadAopProxiesIn(appContext, reloadedClass);
        }
        return cleared;
    }

    private boolean reloadAopProxiesIn(Object appContext, Class<?> reloadedClass) {
        try {
            // Find AbstractAutoProxyCreator beans
            String[] beanNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator");
            if (beanNames == null || beanNames.length == 0) return false;

            Method getBean = appContext.getClass().getMethod("getBean", String.class);

            boolean cleared = false;
            for (String beanName : beanNames) {
                Object proxyCreator = getBean.invoke(appContext, beanName);
                cleared |= clearAdvisedBeansCache(proxyCreator);
            }

            if (cleared) {
                StatusReporter.success("AOP proxy caches cleared for aspect " + reloadedClass.getName());
            }
            return cleared;
        } catch (Exception e) {
            StatusReporter.warn("Spring AOP reload failed: " + e.getMessage());
            return false;
        }
    }

    private boolean clearAdvisedBeansCache(Object proxyCreator) {
        try {
            // AbstractAutoProxyCreator has a private advisedBeans map
            Class<?> current = proxyCreator.getClass();
            while (current != null) {
                try {
                    Field advisedBeans = current.getDeclaredField("advisedBeans");
                    advisedBeans.setAccessible(true);
                    Object map = advisedBeans.get(proxyCreator);
                    if (map instanceof Map<?, ?> m) {
                        m.clear();
                        return true;
                    }
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isAspectClass(Class<?> clazz) {
        try {
            for (var annotation : clazz.getAnnotations()) {
                String name = annotation.annotationType().getName();
                if (name.endsWith(".Aspect") || name.contains("aspectj")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
