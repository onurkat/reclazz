/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.hybris.HybrisContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Handles re-registration of SAP Commerce interceptors after hot-reload.
 *
 * Hybris interceptors (ValidateInterceptor, PrepareInterceptor, etc.) are
 * registered in the InterceptorRegistry. When an interceptor class is reloaded,
 * the registry may still hold a reference to the old interceptor instance.
 *
 * This reloader destroys and re-creates the interceptor Spring bean,
 * which triggers the InterceptorMapping to pick up the new instance.
 */
public class InterceptorReloader {

    /**
     * Reload an interceptor after its class has been hot-swapped.
     *
     * Since interceptors in Hybris are Spring beans registered via
     * InterceptorMapping in Spring XML, destroying and recreating
     * the Spring bean is sufficient to update the interceptor.
     */
    public void reloadInterceptor(String className, HybrisContext context) {
        try {
            // Access the InterceptorRegistry via Registry
            Class<?> registryClass = Class.forName("de.hybris.platform.core.Registry");

            Method hasCurrentTenant = registryClass.getMethod("hasCurrentTenant");
            if (!(Boolean) hasCurrentTenant.invoke(null)) {
                StatusReporter.warn("Tenant not available. Interceptor reload deferred.");
                return;
            }

            Method getCtx = registryClass.getMethod("getApplicationContext");
            Object appContext = getCtx.invoke(null);

            if (appContext == null) {
                StatusReporter.warn("ApplicationContext not available for interceptor reload.");
                return;
            }

            // Find the interceptor bean
            Class<?> interceptorClass = Class.forName(className, false,
                    appContext.getClass().getClassLoader());

            Method getBeanNamesForType = appContext.getClass().getMethod(
                    "getBeanNamesForType", Class.class);
            String[] beanNames = (String[]) getBeanNamesForType.invoke(appContext, interceptorClass);

            if (beanNames.length == 0) {
                StatusReporter.info("No Spring bean found for interceptor: " + className);
                return;
            }

            for (String beanName : beanNames) {
                // Destroy and recreate the bean
                Method getBeanFactory = appContext.getClass().getMethod("getBeanFactory");
                Object beanFactory = getBeanFactory.invoke(appContext);

                Method destroySingleton = findDestroyMethod(beanFactory);
                if (destroySingleton != null) {
                    destroySingleton.invoke(beanFactory, beanName);
                }

                // Recreate by requesting the bean
                Method getBean = appContext.getClass().getMethod("getBean", String.class);
                getBean.invoke(appContext, beanName);

                StatusReporter.success("Interceptor bean re-registered: " + beanName);
            }

            // Refresh only InterceptorMapping beans that reference this interceptor class
            refreshInterceptorMappings(appContext, className);

        } catch (ClassNotFoundException e) {
            StatusReporter.warn("Hybris platform classes not available. " +
                    "Interceptor reload will take effect after server start.");
        } catch (Exception e) {
            StatusReporter.error("Failed to reload interceptor " + className + ": " + e.getMessage());
        }
    }

    /**
     * Refresh only InterceptorMapping beans that reference the given interceptor class.
     * This avoids destroying all mappings when only one interceptor changed.
     */
    private void refreshInterceptorMappings(Object appContext, String interceptorClassName) {
        try {
            Class<?> mappingClass = Class.forName(
                    "de.hybris.platform.servicelayer.interceptor.impl.InterceptorMapping",
                    false, appContext.getClass().getClassLoader());

            Method getBeanNamesForType = appContext.getClass().getMethod(
                    "getBeanNamesForType", Class.class);
            String[] mappingBeans = (String[]) getBeanNamesForType.invoke(appContext, mappingClass);

            if (mappingBeans.length == 0) return;

            Method getBeanFactory = appContext.getClass().getMethod("getBeanFactory");
            Object beanFactory = getBeanFactory.invoke(appContext);
            Method destroyMethod = findDestroyMethod(beanFactory);
            Method getBean = appContext.getClass().getMethod("getBean", String.class);

            int refreshed = 0;
            for (String beanName : mappingBeans) {
                try {
                    // Get the mapping bean and check if its interceptor matches
                    Object mapping = getBean.invoke(appContext, beanName);
                    Method getInterceptor = findGetterMethod(mapping.getClass(), "getInterceptor");
                    if (getInterceptor != null) {
                        Object interceptor = getInterceptor.invoke(mapping);
                        if (interceptor != null &&
                                interceptor.getClass().getName().equals(interceptorClassName)) {
                            if (destroyMethod != null) {
                                destroyMethod.invoke(beanFactory, beanName);
                            }
                            getBean.invoke(appContext, beanName);
                            refreshed++;
                        }
                    }
                } catch (Exception e) {
                    StatusReporter.warn("Could not inspect mapping bean: " + beanName);
                }
            }

            if (refreshed > 0) {
                StatusReporter.info("Refreshed " + refreshed + " InterceptorMapping bean(s) for " + interceptorClassName);
            }

        } catch (ClassNotFoundException e) {
            // InterceptorMapping class not available - that's OK
        } catch (Exception e) {
            StatusReporter.warn("Could not refresh InterceptorMappings: " + e.getMessage());
        }
    }

    private Method findGetterMethod(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method m = current.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Method findDestroyMethod(Object beanFactory) {
        Class<?> current = beanFactory.getClass();
        while (current != null) {
            try {
                Method m = current.getDeclaredMethod("destroySingleton", String.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
