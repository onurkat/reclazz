/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.ReloadEffects;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.util.Reflect;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Recreates Spring Data repository beans after class reload.
 *
 * When a Repository interface or its custom implementation is reloaded,
 * the generated proxy may reference stale method handles.
 * This reloader destroys and recreates the repository bean.
 *
 * All Spring interaction is via reflection, graceful no-op if Spring Data is not present.
 */
public class SpringDataReloader {

    private final PlatformContext platformContext;

    public SpringDataReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Recreate repository beans if the reloaded class is a Spring Data Repository.
     */
    public boolean reloadRepository(Class<?> reloadedClass) {
        if (!isRepository(reloadedClass)) return false;
        var contexts = platformContext.getAllApplicationContexts();
        var replacements = new IdentityHashMap<Object, Object>();
        var refreshed = new ArrayList<String>();
        for (Object context : contexts) reloadRepositoriesIn(context, reloadedClass, replacements, refreshed);
        if (refreshed.isEmpty()) return false;
        SpringBeanReloader.healStaleReferences(contexts, replacements);
        ReloadEffects.note("repository re-created");
        for (String name : refreshed) StatusReporter.detail("Spring Data repository refreshed: " + name);
        return true;
    }

    private void reloadRepositoriesIn(Object appContext, Class<?> reloadedClass,
                                      IdentityHashMap<Object, Object> replacements, List<String> refreshed) {
        try {
            Object beanFactory = SpringBeans.getBeanFactory(appContext);
            // Type discovery must not initialize lazy FactoryBeans.
            String[] names = (String[]) beanFactory.getClass().getMethod("getBeanNamesForType",
                    Class.class, boolean.class, boolean.class).invoke(beanFactory, reloadedClass, true, false);
            Method singleton = required(beanFactory, "getSingleton", String.class);
            Method destroy = required(beanFactory, "destroySingleton", String.class);
            Method clearTypes = required(beanFactory, "clearByTypeCache");
            Class<?> factoryType = Class.forName("org.springframework.beans.factory.FactoryBean", false,
                    beanFactory.getClass().getClassLoader());
            for (String name : names) {
                try {
                    Object owner = singleton.invoke(beanFactory, name);
                    if (owner == null) continue;
                    // isSingleton is queried only after the owner already exists.
                    if (!Boolean.TRUE.equals(beanFactory.getClass().getMethod("isSingleton", String.class)
                            .invoke(beanFactory, name))) continue;
                    Object old = factoryType.isInstance(owner)
                            ? required(beanFactory, "getCachedObjectForFactoryBean", String.class).invoke(beanFactory, name)
                            : owner;
                    // An existing factory is not proof its product is warm. Evict
                    // the factory's old metadata without constructing a cold product;
                    // first access will create the updated factory/product normally.
                    try (var productType = preserveProductType(beanFactory, name, owner, old, factoryType)) {
                        destroy.invoke(beanFactory, name);
                        if (old == null) continue;
                        Object replacement = appContext.getClass().getMethod("getBean", String.class).invoke(appContext, name);
                        // Clearing cannot cancel a lookup that computed an empty
                        // result before the clear and publishes it afterwards.
                        // The scoped product type prevents that empty candidate.
                        clearTypes.invoke(beanFactory);
                        if (!reloadedClass.isInstance(replacement))
                            throw new IllegalStateException("recreated product does not match repository type");
                        if (old != replacement) replacements.put(old, replacement);
                        refreshed.add(name);
                    }
                } catch (Exception e) {
                    StatusReporter.warn("Spring Data repository reload failed for " + name + ": " + Failures.describe(e));
                }
            }
        } catch (Exception e) {
            StatusReporter.warn("Spring Data repository reload failed: " + Failures.describe(e));
        }
    }

    /** Keep the known warm factory product discoverable while its owner is absent/creating. */
    private static AutoCloseable preserveProductType(Object beanFactory, String name, Object owner,
                                                       Object product, Class<?> factoryType) throws Exception {
        if (product == null || !factoryType.isInstance(owner)) return () -> { };
        Object definition = required(beanFactory, "getMergedBeanDefinition", String.class).invoke(beanFactory, name);
        String attribute = (String) factoryType.getField("OBJECT_TYPE_ATTRIBUTE").get(null);
        Method get = required(definition, "getAttribute", String.class);
        // A declared Class/ResolvableType may carry more precise generic metadata.
        if (get.invoke(definition, attribute) != null) return () -> { };
        Object reported = factoryType.getMethod("getObjectType").invoke(owner);
        if (!(reported instanceof Class<?> type) || !type.isInstance(product))
            throw new IllegalStateException("warm factory product type is unavailable");
        Method set = required(definition, "setAttribute", String.class, Object.class);
        Method remove = required(definition, "removeAttribute", String.class);
        set.invoke(definition, attribute, type);
        return () -> {
            // Do not remove metadata another participant replaced in the meantime.
            if (get.invoke(definition, attribute) == type) remove.invoke(definition, attribute);
        };
    }

    /** Marker assignability covers the type itself and every inherited interface. */
    public static boolean isRepository(Class<?> clazz) {
        if (clazz == null) return false;
        try {
            return Class.forName("org.springframework.data.repository.Repository", false, clazz.getClassLoader())
                    .isAssignableFrom(clazz);
        } catch (ClassNotFoundException | LinkageError absent) { return false; }
    }

    private static Method required(Object target, String name, Class<?>... args) throws NoSuchMethodException {
        Method method = Reflect.findMethod(target.getClass(), name, args);
        if (method == null) throw new NoSuchMethodException(name);
        return method;
    }
}
