/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.RestartLedger;

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

    // Spring owns live adapters through its scheduledTasks map. Neither the
    // processor nor its adapters may be kept alive by the agent after context
    // shutdown, so both sides of this index are weak (names are just strings).
    private final Map<Object, Map<String, WeakReference<Object>>> adapters = new WeakHashMap<>();

    public SpringSchedulerReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Re-register @Scheduled methods if the reloaded class has them.
     */
    public boolean reloadScheduledMethods(Class<?> reloadedClass) {
        return reloadScheduledMethods(reloadedClass, Set.of(), null);
    }

    public synchronized boolean reloadScheduledMethods(Class<?> reloadedClass,
                                                        Set<String> addedMethods, byte[] bytecode) {
        AddedScheduledAdapter.Plan plan = AddedScheduledAdapter.inspect(bytecode, addedMethods);
        for (String reason : plan.refused()) report(reloadedClass, reason);
        boolean original = hasScheduledAnnotation(reloadedClass);
        if (!original && plan.methods().isEmpty() && adapters.isEmpty()) return false;
        boolean reloaded = false;
        boolean found = false;
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            String[] processors = SpringBeans.beanNamesForType(appContext,
                    "org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor");
            String[] beans = SpringBeans.beanNamesForType(appContext, reloadedClass);
            for (String processorName : processors) {
                Object processor = SpringBeans.getBean(appContext, processorName);
                if (processor == null) continue;
                for (String beanName : beans) {
                    found = true;
                    try {
                        reloaded |= reloadBean(appContext, processor, beanName, reloadedClass, original, plan);
                    } catch (Throwable failure) {
                        report(reloadedClass, beanName + ": " + Failures.describe(failure));
                    }
                }
            }
        }
        if (!found && !plan.methods().isEmpty())
            report(reloadedClass, "no matching bean with a scheduling processor was found");
        if (reloaded) ReloadEffects.note("@Scheduled re-registered");
        return reloaded;
    }

    private boolean reloadBean(Object context, Object processor, String beanName,
                               Class<?> type, boolean original, AddedScheduledAdapter.Plan plan) throws Throwable {
        Method destroy = processor.getClass().getMethod(
                "postProcessBeforeDestruction", Object.class, String.class);
        Method postProcess = processor.getClass().getMethod(
                "postProcessAfterInitialization", Object.class, String.class);
        Map<String, WeakReference<Object>> owned = adapters.get(processor);
        WeakReference<Object> previous = owned == null ? null : owned.get(beanName);
        Object old = previous == null ? null : previous.get();
        if (old != null) destroy.invoke(processor, old, adapterName(beanName));
        if (owned != null) {
            owned.remove(beanName);
            if (owned.isEmpty()) adapters.remove(processor);
        }

        if (!original && plan.methods().isEmpty()) return old != null;
        Object factory = SpringBeans.getBeanFactory(context);
        if (!(Boolean) factory.getClass().getMethod("isSingleton", String.class).invoke(factory, beanName)) {
            if (!plan.methods().isEmpty()) report(type, beanName + ": only singleton beans are supported");
            return false;
        }
        Object bean = SpringBeans.getBean(context, beanName);
        if (bean == null) throw new IllegalStateException("bean " + beanName + " is unavailable");
        if (original) {
            destroy.invoke(processor, bean, beanName);
            postProcess.invoke(processor, bean, beanName);
        }
        if (plan.methods().isEmpty()) return original;
        // An added method is absent from an existing proxy's advised surface.
        // Unwrapping it would execute the job while silently bypassing advice.
        if (bean.getClass() != type) {
            report(type, beanName + ": added scheduled methods on proxies or subclass instances need a restart");
            return original;
        }
        Object adapter = AddedScheduledAdapter.create(type, currentSingleton(factory, beanName, type), plan);
        try {
            postProcess.invoke(processor, adapter, adapterName(beanName));
        } catch (Throwable failure) {
            // A valid first method may have been registered before a later
            // method's cron/placeholder failed. Do not leave a partial set.
            try { destroy.invoke(processor, adapter, adapterName(beanName)); }
            catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
                report(type, beanName + ": replacement task cancellation failed: " + Failures.describe(cleanup));
                adapters.computeIfAbsent(processor, ignored -> new HashMap<>())
                        .put(beanName, new WeakReference<>(adapter));
            }
            throw failure;
        }
        adapters.computeIfAbsent(processor, ignored -> new HashMap<>())
                .put(beanName, new WeakReference<>(adapter));
        StatusReporter.detail("Added @Scheduled methods registered for " + type.getName() + " (" + beanName + ")");
        return true;
    }

    private static String adapterName(String beanName) {
        // No bean definition is created. This distinguishes the adapter from
        // its real bean for Spring's context-close cancellation bookkeeping.
        return InjectedNames.PREFIX + "scheduled$" + beanName;
    }

    private static java.util.function.Supplier<Object> currentSingleton(Object factory, String beanName,
                                                                       Class<?> type) throws NoSuchMethodException {
        Method read = factory.getClass().getMethod("getSingleton", String.class);
        var warned = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            try {
                // Dependency cascades and property rebinding can replace this
                // bean without reloading its class. Never retain that destroyed
                // instance, and never create one while Spring is rebuilding it.
                Object bean = read.invoke(factory, beanName);
                if (bean != null && bean.getClass() != type) {
                    if (warned.compareAndSet(false, true))
                        report(type, beanName + ": the replacement singleton is a proxy or subclass; added tasks are paused");
                    return null;
                }
                return bean;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read scheduled singleton " + beanName, failure);
            }
        };
    }

    private static void report(Class<?> type, String reason) {
        StatusReporter.warn("@Scheduled reload for " + type.getName() + " could not apply: "
                + reason + ". Correct the declaration and compile again, or restart for unsupported reload shapes.");
        RestartLedger.note(type.getName(), "scheduled declaration not applied: " + reason);
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
