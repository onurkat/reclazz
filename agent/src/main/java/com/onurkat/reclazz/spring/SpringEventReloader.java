/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Refreshes just the edited bean's listeners, including supported added methods. */
public class SpringEventReloader {
    private final PlatformContext platformContext;
    // Spring owns listeners. This bookkeeping must not retain closed contexts,
    // application classloaders, or hidden classes after their last registration.
    private final Map<Object, List<Owned>> adapters = new WeakHashMap<>();
    private record Owned(WeakReference<Class<?>> type, WeakReference<Object> listener) { }

    public SpringEventReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    public boolean reloadEventListeners(Class<?> type) {
        return reloadEventListeners(type, Set.of(), null);
    }

    public synchronized boolean reloadEventListeners(Class<?> type, Set<String> added, byte[] bytes) {
        var plan = AddedEventListenerAdapter.inspect(bytes, added);
        for (String reason : plan.refused()) report(type, reason);
        boolean changed = false;
        boolean found = false;
        for (Object context : platformContext.getAllApplicationContexts()) {
            try {
                Method getMulticaster = Reflect.findMethod(context.getClass(), "getApplicationEventMulticaster");
                if (getMulticaster == null) throw new IllegalStateException("event multicaster is inaccessible");
                Object multicaster = getMulticaster.invoke(context);
                changed |= removeAdded(multicaster, type);
                String[] processors = SpringBeans.beanNamesForType(context,
                        "org.springframework.context.event.EventListenerMethodProcessor");
                if (processors.length == 0) continue;
                if (processors.length != 1) throw new IllegalStateException("multiple event listener processors are unsupported");
                Object processor = SpringBeans.getBean(context, processors[0]);
                Set<String> beans = new LinkedHashSet<>(Arrays.asList(SpringBeans.beanNamesForType(context, type)));
                found |= !beans.isEmpty();
                changed |= refreshOriginal(context, multicaster, processor, type, beans);
                if (!plan.methods().isEmpty()) {
                    for (String bean : beans) {
                        try {
                            List<Object> listeners = prepareAdded(context, processor, type, bean, plan);
                            Method add = multicaster.getClass().getMethod("addApplicationListener", listenerType(multicaster));
                            List<Owned> owned = adapters.computeIfAbsent(multicaster, ignored -> new ArrayList<>());
                            for (Object listener : listeners) {
                                // Index before add: even a custom multicaster that adds and
                                // then throws can be cleaned up by the failure path.
                                owned.add(new Owned(new WeakReference<>(type), new WeakReference<>(listener)));
                                add.invoke(multicaster, listener);
                            }
                            changed |= !listeners.isEmpty();
                        } catch (Throwable failure) {
                            // Cancel this class's partial replacement, keeping any failed
                            // cleanup indexed for retry on its next save.
                            try { removeAdded(multicaster, type); }
                            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                            throw failure;
                        }
                    }
                }
            } catch (Throwable failure) {
                report(type, Failures.describe(failure));
            }
        }
        if (!found && !plan.methods().isEmpty()) report(type, "no matching bean with an event listener processor was found");
        if (changed) ReloadEffects.note("@EventListener re-registered");
        return changed;
    }

    private static boolean refreshOriginal(Object context, Object multicaster, Object processor,
                                           Class<?> type, Set<String> beans) throws Exception {
        Method process = Reflect.findMethod(processor.getClass(), "processBean", String.class, Class.class);
        Method remove = multicaster.getClass().getMethod("removeApplicationListeners", Predicate.class);
        Object registry = context.getClass().getMethod("getApplicationListeners").invoke(context);
        Object negative = Reflect.readField(processor, "nonAnnotatedClasses");
        if (process == null || !(negative instanceof Set<?> cache) || !(registry instanceof Collection<?> listeners))
            throw new IllegalStateException("event listener processor metadata is inaccessible");
        Class<?> base = Class.forName("org.springframework.context.event.ApplicationListenerMethodAdapter",
                false, processor.getClass().getClassLoader());
        Predicate<Object> belongs = listener -> {
            if (!base.isInstance(listener)) return false;
            Object held = Reflect.readField(listener, "method");
            return held instanceof Method method && (method.getDeclaringClass() == type
                    || (beans.contains(Reflect.readField(listener, "beanName"))
                        && method.getDeclaringClass().isAssignableFrom(type)));
        };
        var removed = new java.util.concurrent.atomic.AtomicInteger();
        remove.invoke(multicaster, (Predicate<Object>) listener -> {
            if (!belongs.test(listener)) return false;
            removed.incrementAndGet();
            return true;
        });
        // The context also retains the adapters added by processBean. Removing
        // only from the multicaster leaks old registrations in this second set.
        synchronized (listeners) { listeners.removeIf(belongs); }
        cache.remove(type);
        int before = listeners.size();
        for (String bean : beans) process.invoke(processor, bean, type);
        return removed.get() > 0 || listeners.size() > before;
    }

    private static List<Object> prepareAdded(Object context, Object processor, Class<?> type, String bean,
                                             AddedEventListenerAdapter.Plan plan) throws Throwable {
        Object factory = SpringBeans.getBeanFactory(context);
        if (!(Boolean) factory.getClass().getMethod("isSingleton", String.class).invoke(factory, bean))
            throw new IllegalStateException(bean + ": only singleton beans are supported");
        Supplier<Object> current = currentSingleton(factory, bean, type);
        Object instance = current.get();
        if (instance == null) throw new IllegalStateException(bean + ": singleton is absent or is a proxy/subclass");
        Object factories = Reflect.readField(processor, "eventListenerFactories");
        if (!(factories instanceof List<?> list) || list.isEmpty())
            throw new IllegalStateException("event listener factories are unavailable");
        // A custom factory can interpret names, declaring classes, or advice.
        // A synthetic delegate cannot promise those semantics, even when its
        // copied EventListener annotation happens to satisfy supportsMethod.
        for (Object candidate : list)
            if (!candidate.getClass().getName().equals("org.springframework.context.event.DefaultEventListenerFactory"))
                throw new IllegalStateException("custom event listener factories require a restart for added methods");
        List<Object> listeners = AddedEventListenerAdapter.create(type, bean, current, plan);
        ClassLoader loader = processor.getClass().getClassLoader();
        Class<?> evaluatorType = Class.forName("org.springframework.context.event.EventExpressionEvaluator", false, loader);
        Class<?> contextType = Class.forName("org.springframework.context.ApplicationContext", false, loader);
        Class<?> base = Class.forName("org.springframework.context.event.ApplicationListenerMethodAdapter", false, loader);
        Method init = Reflect.findMethod(base, "init", contextType, evaluatorType);
        if (init == null) throw new IllegalStateException("event adapter initialization is inaccessible");
        Object evaluator = freshEvaluator(evaluatorType, Reflect.readField(processor, "evaluator"));
        for (Object listener : listeners) init.invoke(listener, context, evaluator);
        return listeners;
    }

    private static Object freshEvaluator(Class<?> type, Object original) throws Exception {
        if (original == null) return null; // Spring's explicit spring.spel.ignore mode.
        java.lang.reflect.Constructor<?> ctor;
        Object[] args;
        try {
            ctor = type.getDeclaredConstructor(); // Spring 5.3
            args = new Object[0];
        } catch (NoSuchMethodException spring61) {
            Class<?> evaluation = Class.forName("org.springframework.expression.spel.support.StandardEvaluationContext",
                    false, type.getClassLoader());
            ctor = type.getDeclaredConstructor(evaluation);
            Object delegates = Reflect.readField(original, "originalEvaluationContext");
            if (delegates == null) throw new IllegalStateException("event evaluation context is inaccessible");
            args = new Object[]{delegates};
        }
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private boolean removeAdded(Object multicaster, Class<?> type) throws Exception {
        List<Owned> owned = adapters.get(multicaster);
        if (owned == null) return false;
        Method remove = multicaster.getClass().getMethod("removeApplicationListener", listenerType(multicaster));
        boolean changed = false;
        for (Iterator<Owned> it = owned.iterator(); it.hasNext();) {
            Owned entry = it.next();
            Object listener = entry.listener().get();
            if (listener == null) { it.remove(); continue; }
            if (entry.type().get() != type) continue;
            remove.invoke(multicaster, listener);
            it.remove();
            changed = true;
        }
        if (owned.isEmpty()) adapters.remove(multicaster);
        return changed;
    }

    private static Class<?> listenerType(Object multicaster) throws ClassNotFoundException {
        return Class.forName("org.springframework.context.ApplicationListener", false, multicaster.getClass().getClassLoader());
    }

    private static Supplier<Object> currentSingleton(Object factory, String name, Class<?> type) throws Exception {
        Method read = factory.getClass().getMethod("getSingleton", String.class);
        var warned = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            try {
                Object bean = read.invoke(factory, name);
                if (bean != null && bean.getClass() != type) {
                    if (warned.compareAndSet(false, true)) report(type, name + ": proxies or subclass instances are unsupported; added listener paused");
                    return null;
                }
                return bean;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read event singleton " + name, failure);
            }
        };
    }

    private static void report(Class<?> type, String reason) {
        StatusReporter.warn("@EventListener reload for " + type.getName() + " could not apply: " + reason
                + ". Correct the declaration and compile again, or restart for unsupported reload shapes.");
        RestartLedger.note(type.getName(), "event listener declaration not applied: " + reason);
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
            Object retriever = Reflect.readField(multicaster, "defaultRetriever");
            if (retriever == null) return 0;
            Object listeners = Reflect.readField(retriever, "applicationListeners");
            if (!(listeners instanceof java.util.Collection<?> collection)) return 0;

            Method remove = Reflect.findMethod(multicaster.getClass(),
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
                Object method = Reflect.readField(listener, "method");
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

}
