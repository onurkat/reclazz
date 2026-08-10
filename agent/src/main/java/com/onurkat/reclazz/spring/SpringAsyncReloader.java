/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Re-processes @Async beans after class reload.
 *
 * When a class with @Async methods is reloaded, the async proxy may reference
 * stale method implementations. This reloader re-processes the bean through
 * AsyncAnnotationBeanPostProcessor.
 *
 * All Spring interaction is via reflection — graceful no-op if Spring Async is not present.
 */
public class SpringAsyncReloader {

    private final PlatformContext platformContext;

    public SpringAsyncReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Re-process @Async beans if the reloaded class has @Async methods.
     */
    public boolean reloadAsyncMethods(Class<?> reloadedClass) {
        if (!hasAsyncAnnotation(reloadedClass)) return false;

        boolean reloaded = false;
        // The async post-processor may live in any context.
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            reloaded |= reloadAsyncMethodsIn(appContext, reloadedClass);
        }
        return reloaded;
    }

    private boolean reloadAsyncMethodsIn(Object appContext, Class<?> reloadedClass) {
        try {
            String[] beanNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor");
            if (beanNames == null || beanNames.length == 0) return false;

            Method getBean = appContext.getClass().getMethod("getBean", String.class);
            Object processor = getBean.invoke(appContext, beanNames[0]);

            String targetBeanName = SpringBeans.findBeanName(appContext, reloadedClass);
            if (targetBeanName != null) {
                Object bean = getBean.invoke(appContext, targetBeanName);

                Method postProcess = processor.getClass().getMethod(
                        "postProcessAfterInitialization", Object.class, String.class);
                postProcess.invoke(processor, bean, targetBeanName);

                StatusReporter.success("@Async methods re-processed for " + reloadedClass.getName());
                return true;
            }
        } catch (Exception e) {
            StatusReporter.warn("Spring async reload failed: " + e.getMessage());
        }
        return false;
    }

    private boolean hasAsyncAnnotation(Class<?> clazz) {
        try {
            // Check class-level
            for (var annotation : clazz.getAnnotations()) {
                if (annotation.annotationType().getName().contains("Async") ||
                        annotation.annotationType().getName().contains("EnableAsync")) {
                    return true;
                }
            }
            // Check method-level
            for (var method : clazz.getDeclaredMethods()) {
                for (var annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName().contains("Async")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
