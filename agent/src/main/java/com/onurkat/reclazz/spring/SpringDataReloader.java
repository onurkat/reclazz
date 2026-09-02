/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Recreates Spring Data repository beans after class reload.
 *
 * When a Repository interface or its custom implementation is reloaded,
 * the generated proxy may reference stale method handles.
 * This reloader destroys and recreates the repository bean.
 *
 * All Spring interaction is via reflection — graceful no-op if Spring Data is not present.
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
        if (!isRepositoryClass(reloadedClass)) return false;

        boolean reloaded = false;
        // Repository beans may live in any context.
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            reloaded |= reloadRepositoryIn(appContext, reloadedClass);
        }
        return reloaded;
    }

    private boolean reloadRepositoryIn(Object appContext, Class<?> reloadedClass) {
        try {
            String beanName = SpringBeans.findBeanName(appContext, reloadedClass);
            if (beanName == null) return false;

            // Destroy and recreate the repository bean
            Object beanFactory = SpringBeans.getBeanFactory(appContext);

            Method destroySingleton = SpringBeans.findMethod(beanFactory.getClass(),
                    "destroySingleton", String.class);
            if (destroySingleton != null) {
                destroySingleton.invoke(beanFactory, beanName);
            }

            SpringBeans.getBean(appContext, beanName);

            StatusReporter.success("Spring Data repository refreshed: " + reloadedClass.getName());
            return true;
        } catch (Exception e) {
            StatusReporter.warn("Spring Data repository reload failed: " + com.onurkat.reclazz.ui.Failures.describe(e));
            return false;
        }
    }

    private boolean isRepositoryClass(Class<?> clazz) {
        try {
            // Check if class implements Repository or has @Repository annotation
            for (var annotation : clazz.getAnnotations()) {
                if (annotation.annotationType().getName().contains("Repository")) {
                    return true;
                }
            }
            // Check interfaces for Repository hierarchy
            for (Class<?> iface : getAllInterfaces(clazz)) {
                String name = iface.getName();
                if (name.equals("org.springframework.data.repository.Repository") ||
                        name.equals("org.springframework.data.repository.CrudRepository") ||
                        name.equals("org.springframework.data.jpa.repository.JpaRepository") ||
                        name.equals("org.springframework.data.repository.PagingAndSortingRepository") ||
                        name.equals("org.springframework.data.repository.reactive.ReactiveCrudRepository")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Class<?>[] getAllInterfaces(Class<?> clazz) {
        java.util.Set<Class<?>> interfaces = new java.util.LinkedHashSet<>();
        Class<?> current = clazz;
        while (current != null) {
            for (Class<?> iface : current.getInterfaces()) {
                interfaces.add(iface);
                // Also add super-interfaces
                for (Class<?> superIface : iface.getInterfaces()) {
                    interfaces.add(superIface);
                }
            }
            current = current.getSuperclass();
        }
        return interfaces.toArray(new Class[0]);
    }
}
