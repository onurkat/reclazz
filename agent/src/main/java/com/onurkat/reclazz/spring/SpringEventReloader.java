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

            // The old adapters have to go first, and precisely. Re-processing
            // ADDS listeners: it re-scans the beans and registers what it
            // finds, so without this the class's listener is registered a
            // second time and every event is handled twice from then on.
            // Measured: one publish called the method once before a reload and
            // twice after, for a listener whose side effects are the whole
            // reason it exists. The old code looked for a removeAllListeners
            // that does not exist, decided it would be too aggressive anyway,
            // and removed nothing.
            //
            // Too aggressive was the right worry about the wrong method. Only
            // the adapters for THIS class are removed, matched on the method
            // they hold, and removeApplicationListener clears the multicaster's
            // per-event-type cache as it goes, which is the half a direct set
            // removal would have missed.
            if (multicasterNames != null && multicasterNames.length > 0) {
                Object multicaster = getBean.invoke(appContext, multicasterNames[0]);
                removeAdaptersFor(multicaster, reloadedClass);
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

    /**
     * Take out every {@code @EventListener} adapter that belongs to this class.
     *
     * <p>An adapter holds the {@code Method} it calls, which is what says whose
     * it is: redefinition keeps the Class object, so the method an adapter
     * registered before the reload still reports the same declaring class. The
     * name is compared rather than the Class, so an adapter left behind by a
     * previous classloader is matched too.
     *
     * @return how many were removed
     */
    static int removeAdaptersFor(Object multicaster, Class<?> reloadedClass) {
        int removed = 0;
        try {
            Object retriever = readField(multicaster, "defaultRetriever");
            if (retriever == null) return 0;
            Object listeners = readField(retriever, "applicationListeners");
            if (!(listeners instanceof java.util.Collection<?> collection)) return 0;

            Method remove = SpringBeans.findMethod(multicaster.getClass(),
                    "removeApplicationListener",
                    Class.forName("org.springframework.context.ApplicationListener",
                            false, multicaster.getClass().getClassLoader()));
            if (remove == null) return 0;

            // Copied first: removing from the live set while walking it is how
            // a reload turns into a ConcurrentModificationException.
            for (Object listener : new java.util.ArrayList<>(collection)) {
                if (listener == null) continue;
                if (!listener.getClass().getName().endsWith("ApplicationListenerMethodAdapter")) {
                    continue;
                }
                Object method = readField(listener, "method");
                if (!(method instanceof Method held)) continue;
                if (!held.getDeclaringClass().getName().equals(reloadedClass.getName())) continue;
                remove.invoke(multicaster, listener);
                removed++;
            }
        } catch (Throwable notThisShape) {
            // A multicaster that cannot be read keeps its listeners, which is
            // the behaviour that came before this.
        }
        return removed;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
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
