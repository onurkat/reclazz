/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Generic Spring bean reloader that works with any Spring application.
 * Refreshes singleton beans by destroying and recreating them.
 *
 * All Spring interaction is via reflection — no compile-time Spring dependency.
 * Graceful no-op if Spring is not present.
 */
public class SpringBeanReloader {

    private final PlatformContext platformContext;

    public SpringBeanReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Refresh a specific Spring bean after its class has been reloaded.
     */
    public void refreshBean(String className) {
        refreshBean(className, null);
    }

    /**
     * Refresh with the actual reloaded Class when available — class-object
     * type matching works across contexts regardless of classloaders.
     */
    public void refreshBean(String className, Class<?> reloadedClass) {
        try {
            // Iterate every live context (global + web contexts): the bean
            // may be defined in any of them, or in several.
            var contexts = platformContext.getAllApplicationContexts();
            if (contexts.isEmpty()) {
                // Spring context isn't ready yet. Reloads triggered during
                // server startup (before the context is built) have nothing
                // to refresh — Spring will instantiate the new class version
                // when it eventually wires beans. Stay silent: spamming this
                // on every reload is noise.
                return;
            }

            // Phase 1: refresh the bean itself wherever it is DEFINED.
            // Remember old→new instance pairs for reference healing below.
            java.util.LinkedHashSet<String> refreshedNames = new java.util.LinkedHashSet<>();
            java.util.IdentityHashMap<Object, Object> replacements = new java.util.IdentityHashMap<>();
            int totalRefreshed = 0;
            for (Object appContext : contexts) {
                String[] beanNames = (reloadedClass != null)
                        ? findBeanNamesByType(appContext, reloadedClass)
                        : findBeanNamesByClass(appContext, className);
                for (String beanName : beanNames) {
                    Object[] pair = destroyAndRefreshBean(appContext, beanName);
                    if (pair != null && pair[0] != null && pair[1] != null && pair[0] != pair[1]) {
                        replacements.put(pair[0], pair[1]);
                    }
                    refreshedNames.add(beanName);
                    StatusReporter.success("Spring bean refreshed: " + beanName);
                    totalRefreshed++;
                }
            }

            if (totalRefreshed == 0) {
                StatusReporter.info("No Spring bean found for class: " + className);
                return;
            }

            // Phases 2 and 3 sweep every singleton in every context, so in a
            // batch they run ONCE at the end rather than per reloaded class.
            if (batchDepth > 0) {
                batchRefreshedNames.addAll(refreshedNames);
                batchReplacements.putAll(replacements);
                return;
            }
            applyDependentsAndHealing(contexts, refreshedNames, replacements);

        } catch (Exception e) {
            StatusReporter.error("Failed to refresh Spring bean for " + className + ": " + com.onurkat.reclazz.ui.Failures.describe(e));
        }
    }

    /**
     * Phase 2 (dependent cascade) + phase 3 (stale-reference healing).
     *
     * Phase 2: a bean that @Autowired the refreshed one records that
     * dependency in ITS OWN factory — e.g. a controller in a child
     * DispatcherServlet context depending on a service from the parent.
     * Phase 3: dependency bookkeeping is best-effort (edges are scrubbed on
     * destroy), so also sweep live singletons and re-point fields that still
     * hold a destroyed instance. Identity comparison — no type guessing.
     */
    private void applyDependentsAndHealing(java.util.List<Object> contexts,
                                           java.util.LinkedHashSet<String> refreshedNames,
                                           java.util.IdentityHashMap<Object, Object> replacements) {
        cascadeDependents(contexts, refreshedNames);
        if (!replacements.isEmpty()) {
            int healed = healStaleReferences(contexts, replacements);
            if (healed > 0) {
                StatusReporter.info("Re-pointed " + com.onurkat.reclazz.ui.Plural.of(healed, "stale reference")
                        + " to refreshed bean instances");
            }
        }
    }

    // ---- Batch mode -------------------------------------------------
    // A single save-all can reload dozens of classes. Cascading and
    // healing per class means one full singleton sweep per class; batched,
    // it is one sweep for the whole reload.

    private int batchDepth;
    private final java.util.LinkedHashSet<String> batchRefreshedNames = new java.util.LinkedHashSet<>();
    private final java.util.IdentityHashMap<Object, Object> batchReplacements = new java.util.IdentityHashMap<>();

    /** Defer dependent cascade and healing until {@link #endBatch()}. */
    public void beginBatch() {
        batchDepth++;
    }

    /** Run the deferred cascade/healing once for everything refreshed. */
    public void endBatch() {
        if (batchDepth == 0) return;
        if (--batchDepth > 0) return;
        if (batchRefreshedNames.isEmpty() && batchReplacements.isEmpty()) return;
        try {
            applyDependentsAndHealing(platformContext.getAllApplicationContexts(),
                    new java.util.LinkedHashSet<>(batchRefreshedNames),
                    new java.util.IdentityHashMap<>(batchReplacements));
        } catch (Exception e) {
            StatusReporter.error("Deferred Spring refresh failed: " + com.onurkat.reclazz.ui.Failures.describe(e));
        } finally {
            batchRefreshedNames.clear();
            batchReplacements.clear();
        }
    }

    /** Type match with the ACTUAL reloaded Class — classloader-exact. */
    private String[] findBeanNamesByType(Object appContext, Class<?> targetClass) {
        try {
            Method getBeanNamesForType = appContext.getClass().getMethod(
                    "getBeanNamesForType", Class.class);
            String[] names = (String[]) getBeanNamesForType.invoke(appContext, targetClass);
            return names != null ? names : new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    private String[] findBeanNamesByClass(Object appContext, String className) throws Exception {
        try {
            Class<?> targetClass = Class.forName(className, false,
                    appContext.getClass().getClassLoader());
            Method getBeanNamesForType = appContext.getClass().getMethod(
                    "getBeanNamesForType", Class.class);
            return (String[]) getBeanNamesForType.invoke(appContext, targetClass);
        } catch (ClassNotFoundException e) {
            return new String[0];
        }
    }

    /** @return {oldInstance, newInstance} for singletons, else null */
    /**
     * Package-private and static: the property rebinder and the security
     * reloader recreate beans by name through this same door, so a bean is
     * destroyed and rebuilt in exactly one way everywhere.
     */
    static Object[] destroyAndRefreshBean(Object appContext, String beanName) throws Exception {
        Object beanFactory = SpringBeans.getBeanFactory(appContext);

        Method isSingleton = beanFactory.getClass().getMethod("isSingleton", String.class);
        boolean singleton = (Boolean) isSingleton.invoke(beanFactory, beanName);

        if (singleton) {
            Object oldInstance = null;
            Method getSingleton = com.onurkat.reclazz.util.Reflect.findMethod(beanFactory.getClass(),
                    "getSingleton", String.class);
            if (getSingleton != null) {
                oldInstance = getSingleton.invoke(beanFactory, beanName);
            }

            Method destroySingleton = com.onurkat.reclazz.util.Reflect.findMethod(beanFactory.getClass(),
                    "destroySingleton", String.class);
            if (destroySingleton != null) {
                destroySingleton.invoke(beanFactory, beanName);
            }

            Method getBean = appContext.getClass().getMethod("getBean", String.class);
            Object newInstance = getBean.invoke(appContext, beanName);
            return new Object[] { oldInstance, newInstance };
        } else {
            StatusReporter.info("Bean '" + beanName + "' is not a singleton. " +
                    "New instances will use the updated class automatically.");
            return null;
        }
    }

    /**
     * Sweep every live singleton in every context and re-point fields that
     * still hold a destroyed bean instance to its replacement. This is what
     * actually fixes the "existing @Autowired references still hold the old
     * instance" problem — dependency-map cascading alone cannot, because
     * Spring scrubs those edges during destroySingleton.
     */
    /**
     * Per-class list of reference-typed instance fields, computed once.
     *
     * getDeclaredFields() allocates a fresh Field[] (with a full copy of
     * each Field) on EVERY call, and setAccessible has its own cost — at
     * ~10k singletons a naive sweep re-derived hundreds of thousands of
     * Field objects per reload. Class objects are weakly held so unloaded
     * classes (including our own hidden companions) stay collectable.
     */
    private static final java.util.Map<Class<?>, java.lang.reflect.Field[]> FIELD_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static java.lang.reflect.Field[] candidateFields(Class<?> type) {
        return FIELD_CACHE.computeIfAbsent(type, t -> {
            java.util.List<java.lang.reflect.Field> out = new java.util.ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                java.lang.reflect.Field[] declared;
                try {
                    declared = c.getDeclaredFields();
                } catch (Throwable e) {
                    continue;
                }
                for (java.lang.reflect.Field f : declared) {
                    if (f.getType().isPrimitive()) continue;
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                    } catch (Throwable e) {
                        continue; // module-protected — never readable, skip forever
                    }
                    out.add(f);
                }
            }
            return out.toArray(new java.lang.reflect.Field[0]);
        });
    }

    /** Package-private and static for the same reason as {@link #destroyAndRefreshBean}. */
    static int healStaleReferences(java.util.List<Object> contexts,
                                   java.util.IdentityHashMap<Object, Object> replacements) {
        // Only fields whose declared type could possibly hold one of the
        // replaced instances are worth reading. Assignability is checked
        // against the field's declared type, so an interface- or
        // Object-typed field is still considered.
        java.util.List<Class<?>> staleTypes = new java.util.ArrayList<>();
        for (Object stale : replacements.keySet()) {
            staleTypes.add(stale.getClass());
        }

        int healed = 0;
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object appContext : contexts) {
            try {
                Object beanFactory = SpringBeans.getBeanFactory(appContext);
                Method getSingletonNames = com.onurkat.reclazz.util.Reflect.findMethod(beanFactory.getClass(), "getSingletonNames");
                Method getSingleton = com.onurkat.reclazz.util.Reflect.findMethod(beanFactory.getClass(), "getSingleton", String.class);
                if (getSingletonNames == null || getSingleton == null) continue;

                String[] names = (String[]) getSingletonNames.invoke(beanFactory);
                for (String name : names) {
                    Object bean;
                    try {
                        bean = getSingleton.invoke(beanFactory, name);
                    } catch (Exception e) {
                        continue;
                    }
                    if (bean == null || !visited.add(bean)) continue;
                    healed += swapFields(bean, replacements, staleTypes);
                }
            } catch (Exception e) {
                StatusReporter.warn("Stale-reference healing skipped a context: " + e);
            }
        }
        return healed;
    }

    private static int swapFields(Object bean,
                                  java.util.IdentityHashMap<Object, Object> replacements,
                                  java.util.List<Class<?>> staleTypes) {
        int swapped = 0;
        for (java.lang.reflect.Field f : candidateFields(bean.getClass())) {
            if (!couldHold(f.getType(), staleTypes)) continue;
            try {
                Object value = f.get(bean);
                Object replacement = (value != null) ? replacements.get(value) : null;
                if (replacement != null) {
                    f.set(bean, replacement);
                    swapped++;
                }
            } catch (Throwable ignored) {
                // final/inaccessible at write time — leave the field alone
            }
        }
        return swapped;
    }

    /** True if a field of this declared type could reference a stale bean. */
    private static boolean couldHold(Class<?> fieldType, java.util.List<Class<?>> staleTypes) {
        for (Class<?> stale : staleTypes) {
            if (fieldType.isAssignableFrom(stale)) return true;
        }
        return false;
    }

    /**
     * Iteratively refresh singletons that depend on any of the given bean
     * names, in every live context, until a fixpoint is reached. Dependency
     * registrations live in the factory that CREATED the dependent bean —
     * so each context's own factory must be queried.
     */
    private void cascadeDependents(java.util.List<Object> contexts,
                                   java.util.LinkedHashSet<String> refreshedNames) {
        java.util.Set<String> done = new java.util.HashSet<>();
        boolean progress = true;
        int guard = 0;
        while (progress && guard++ < 5) {
            progress = false;
            for (Object appContext : contexts) {
                try {
                    Object beanFactory = SpringBeans.getBeanFactory(appContext);
                    Method getDependentBeans = com.onurkat.reclazz.util.Reflect.findMethod(beanFactory.getClass(),
                            "getDependentBeans", String.class);
                    if (getDependentBeans == null) continue;

                    for (String name : java.util.List.copyOf(refreshedNames)) {
                        String[] dependents = (String[]) getDependentBeans.invoke(beanFactory, name);
                        if (dependents == null) continue;
                        for (String dep : dependents) {
                            String key = System.identityHashCode(appContext) + "@" + dep;
                            if (!done.add(key)) continue;
                            try {
                                destroyAndRefreshBean(appContext, dep);
                                refreshedNames.add(dep);
                                progress = true;
                                StatusReporter.info("Dependent bean re-wired: " + dep);
                            } catch (Exception e) {
                                StatusReporter.warn("Could not re-wire dependent bean '" + dep + "': " + com.onurkat.reclazz.ui.Failures.describe(e));
                            }
                        }
                    }
                } catch (Exception e) {
                    // Do not silently skip a context — that hides genuine
                    // cascade failures (e.g. a servlet context whose factory
                    // rejects the reflective dependent-beans lookup).
                    StatusReporter.warn("Dependent cascade skipped a context: " + e);
                }
            }
        }
    }
}
