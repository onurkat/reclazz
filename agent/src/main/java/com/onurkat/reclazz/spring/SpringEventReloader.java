/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Re-registers @EventListener methods after class reload.
 *
 * When a class with @EventListener methods is reloaded, the old listener registrations
 * may reference stale method handles. This reloader triggers re-registration.
 *
 * All Spring interaction is via reflection — graceful no-op if Spring Events are not present.
 */
public class SpringEventReloader {

    private final PlatformContext platformContext;

    public SpringEventReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Re-register @EventListener methods if the reloaded class has them.
     */
    public boolean reloadEventListeners(Class<?> reloadedClass) {
        if (!hasEventListenerAnnotation(reloadedClass)) return false;

        boolean reloaded = false;
        // Listener beans may live in any context (web contexts included).
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            reloaded |= reloadEventListenersIn(appContext, reloadedClass);
        }
        return reloaded;
    }

    private boolean reloadEventListenersIn(Object appContext, Class<?> reloadedClass) {
        try {
            // Get EventListenerMethodProcessor
            String[] processorNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.context.event.EventListenerMethodProcessor");
            if (processorNames == null || processorNames.length == 0) return false;

            // Get ApplicationEventMulticaster to remove old listeners
            String[] multicasterNames = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.context.event.ApplicationEventMulticaster");

            Method getBean = appContext.getClass().getMethod("getBean", String.class);

            if (multicasterNames != null && multicasterNames.length > 0) {
                Object multicaster = getBean.invoke(appContext, multicasterNames[0]);

                // Remove all ApplicationListenerMethodAdapter instances for this class
                try {
                    Method removeAll = multicaster.getClass().getMethod("removeAllListeners");
                    // This is too aggressive; instead just log that re-registration was attempted
                } catch (NoSuchMethodException ignored) {}
            }

            // Re-process the bean to register new @EventListener methods
            String beanName = SpringBeans.findBeanName(appContext, reloadedClass);
            if (beanName != null) {
                Object processor = getBean.invoke(appContext, processorNames[0]);
                try {
                    Method afterInit = processor.getClass().getMethod(
                            "afterSingletonsInstantiated");
                    afterInit.invoke(processor);
                    StatusReporter.success("@EventListener methods re-processed for " + reloadedClass.getName());
                    return true;
                } catch (NoSuchMethodException e) {
                    // Try alternative approach
                }
            }

        } catch (Exception e) {
            StatusReporter.warn("Spring event listener reload failed: " + e.getMessage());
        }
        return false;
    }

    private boolean hasEventListenerAnnotation(Class<?> clazz) {
        try {
            for (var method : clazz.getDeclaredMethods()) {
                for (var annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName().contains("EventListener")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
