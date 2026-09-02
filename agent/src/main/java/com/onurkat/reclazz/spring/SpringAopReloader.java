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
                cleared |= clearParsedAdvisors(proxyCreator);
            }

            if (cleared) {
                StatusReporter.success("AOP advice re-read for aspect " + reloadedClass.getName()
                        + ": the pointcut is parsed again, so beans proxied from here on match "
                        + "it as written.");
                // Said because it is the half a developer will otherwise hunt
                // for: a pointcut that starts matching a bean built before the
                // edit cannot reach that bean, whose proxy was decided when it
                // was created.
                StatusReporter.warn("Beans already proxied keep the advice they were built "
                        + "with; save the advised class, or restart, to apply a pointcut that "
                        + "now matches something new.");
                com.onurkat.reclazz.agent.RestartLedger.note(reloadedClass.getName(),
                        "a pointcut change that beans proxied before it still do not match");
            }
            return cleared;
        } catch (Exception e) {
            StatusReporter.warn("Spring AOP reload failed: " + com.onurkat.reclazz.ui.Failures.describe(e));
            return false;
        }
    }

    /**
     * The parsed pointcuts, which is what a changed expression actually is.
     *
     * <p>{@code advisedBeans} only records whether a bean was advised. The
     * pointcut itself is parsed once per aspect bean and kept in the advisor
     * builder's own cache, so editing an expression reloaded the aspect and
     * left every proxy matching the old one. Measured on Boot 3.3: changing a
     * pointcut to match a method that was not matched before did nothing at
     * all. Clearing the cache and the bean-name list makes the next proxy
     * built read the expression as it is now.
     */
    private boolean clearParsedAdvisors(Object proxyCreator) {
        Object builder = readField(proxyCreator, "aspectJAdvisorsBuilder");
        if (builder == null) return false;

        boolean cleared = false;
        Object cache = readField(builder, "advisorsCache");
        if (cache instanceof Map<?, ?> map) {
            map.clear();
            cleared = true;
        }
        // The builder skips the whole scan when it already has the names, so
        // the cache alone would be refilled from nothing.
        try {
            for (Class<?> c = builder.getClass(); c != null && c != Object.class;
                    c = c.getSuperclass()) {
                try {
                    Field names = c.getDeclaredField("aspectBeanNames");
                    names.setAccessible(true);
                    names.set(builder, null);
                    cleared = true;
                    break;
                } catch (NoSuchFieldException keepWalking) {
                    // the next class up may declare it
                }
            }
        } catch (Throwable notThisShape) {
            // A builder that will not be reset keeps the pointcuts it parsed.
        }
        return cleared;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException keepWalking) {
                // the next class up may declare it
            } catch (Throwable notReadable) {
                return null;
            }
        }
        return null;
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
