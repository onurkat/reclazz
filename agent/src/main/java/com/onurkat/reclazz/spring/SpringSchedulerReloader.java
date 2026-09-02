/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Re-registers @Scheduled methods after class reload.
 *
 * When a class with @Scheduled methods is reloaded, the old scheduled tasks continue
 * running with the old method implementations. This reloader cancels old tasks and
 * re-registers them with the updated methods.
 *
 * All Spring interaction is via reflection — graceful no-op if Spring Scheduling is not present.
 */
public class SpringSchedulerReloader {

    private final PlatformContext platformContext;

    public SpringSchedulerReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Re-register @Scheduled methods if the reloaded class has them.
     */
    public boolean reloadScheduledMethods(Class<?> reloadedClass) {
        if (!hasScheduledAnnotation(reloadedClass)) return false;

        boolean reloaded = false;
        // The scheduling post-processor may live in any context.
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            reloaded |= reloadScheduledMethodsIn(appContext, reloadedClass);
        }
        return reloaded;
    }

    private boolean reloadScheduledMethodsIn(Object appContext, Class<?> reloadedClass) {
        try {
            // Get ScheduledAnnotationBeanPostProcessor
            String[] beanNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor");
            if (beanNames == null || beanNames.length == 0) return false;

            Method getBean = appContext.getClass().getMethod("getBean", String.class);
            Object processor = getBean.invoke(appContext, beanNames[0]);

            // Call postProcessBeforeDestruction to cancel old tasks
            try {
                Method destroy = processor.getClass().getMethod(
                        "postProcessBeforeDestruction", Object.class, String.class);
                String beanName = SpringBeans.findBeanName(appContext, reloadedClass);
                if (beanName != null) {
                    Object bean = getBean.invoke(appContext, beanName);
                    destroy.invoke(processor, bean, beanName);

                    // Re-process to register new @Scheduled methods
                    Method postProcess = processor.getClass().getMethod(
                            "postProcessAfterInitialization", Object.class, String.class);
                    postProcess.invoke(processor, bean, beanName);

                    StatusReporter.success("@Scheduled methods re-registered for " + reloadedClass.getName());
                    return true;
                }
            } catch (NoSuchMethodException e) {
                // Older Spring version, skip
            }

        } catch (Exception e) {
            StatusReporter.warn("Spring scheduler reload failed: " + com.onurkat.reclazz.ui.Failures.describe(e));
        }
        return false;
    }

    private boolean hasScheduledAnnotation(Class<?> clazz) {
        try {
            for (var method : clazz.getDeclaredMethods()) {
                for (var annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName().contains("Scheduled")) {
                        return true;
                    }
                }
            }
            for (var annotation : clazz.getAnnotations()) {
                if (annotation.annotationType().getName().contains("EnableScheduling")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
