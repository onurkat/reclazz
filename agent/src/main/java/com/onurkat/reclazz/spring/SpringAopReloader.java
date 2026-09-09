/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.util.Map;
import com.onurkat.reclazz.ui.Failures;

/** Re-parses @Aspect advice and updates supported living Spring singleton proxies in place.
 * All Spring interaction is reflective; the agent has no Spring/AspectJ dependency.
 */
public class SpringAopReloader {

    private final PlatformContext platformContext;

    public SpringAopReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Re-parse advice and refresh existing proxy chains if the class is an @Aspect.
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
            Object factory = SpringBeans.getBeanFactory(appContext);
            if (factory == null) return false;
            ClassLoader loader = appContext.getClass().getClassLoader();
            Class<?> creatorType = Class.forName(
                    "org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator", false, loader);
            String[] beanNames = (String[]) factory.getClass()
                    .getMethod("getBeanNamesForType", Class.class, boolean.class, boolean.class)
                    .invoke(factory, creatorType, false, false);
            if (beanNames.length == 0) return false;
            if (beanNames.length != 1) {
                SpringAopProxyRefresh.skipped(reloadedClass.getName(), "multiple auto-proxy creators");
                return false;
            }
            for (var annotation : reloadedClass.getAnnotations()) {
                if (annotation.annotationType().getName().equals("org.aspectj.lang.annotation.Aspect")
                        && !((String) annotation.annotationType().getMethod("value").invoke(annotation)).isEmpty()) {
                    SpringAopProxyRefresh.skipped(reloadedClass.getName(), "per-clause aspect");
                    return false;
                }
            }
            for (var field : reloadedClass.getDeclaredFields()) for (var annotation : field.getAnnotations()) {
                if (annotation.annotationType().getName().equals("org.aspectj.lang.annotation.DeclareParents")) {
                    SpringAopProxyRefresh.skipped(reloadedClass.getName(), "aspect introductions");
                    return false;
                }
            }
            // New reflection objects are needed as well as new parsed pointcuts.
            for (String utility : new String[]{"org.springframework.util.ReflectionUtils",
                    "org.springframework.core.annotation.AnnotationUtils"}) {
                Class.forName(utility, false, loader).getMethod("clearCache").invoke(null);
            }
            Object proxyCreator = appContext.getClass().getMethod("getBean", String.class)
                    .invoke(appContext, beanNames[0]);
            boolean cleared = clearAdvisedBeansCache(proxyCreator);
            if (!clearParsedAdvisors(proxyCreator)) {
                SpringAopProxyRefresh.skipped(reloadedClass.getName(), "advisor cache layout unavailable");
                return cleared;
            }
            cleared = true;
            int updated = SpringAopProxyRefresh.refresh(factory, proxyCreator, reloadedClass);
            ReloadEffects.note("aspect advice re-read");
            if (updated > 0) ReloadEffects.note("AOP proxies updated");
            StatusReporter.detail("AOP advice re-read for aspect " + reloadedClass.getName()
                    + "; updated " + updated + " existing singleton proxies in place.");
            return cleared;
        } catch (Exception e) {
            SpringAopProxyRefresh.skipped(reloadedClass.getName(), "reload failed: " + Failures.describe(e));
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

        Object cache = readField(builder, "advisorsCache");
        if (!(cache instanceof Map<?, ?> map)) return false;
        // Both cache and name-list reset must be available before rebuilding advisors.
        try {
            for (Class<?> c = builder.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field names = c.getDeclaredField("aspectBeanNames");
                    names.setAccessible(true);
                    if (!java.util.List.class.isAssignableFrom(names.getType())) return false;
                    names.set(builder, null);
                    map.clear();
                    return true;
                } catch (NoSuchFieldException next) { /* inherited cache */ }
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
        return false;
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
                if (name.equals("org.aspectj.lang.annotation.Aspect")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
