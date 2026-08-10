/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generic Spring MVC reloader that re-scans @RequestMapping methods after reload.
 * Works with any Spring MVC application (not just Hybris).
 *
 * All Spring interaction is via reflection — no compile-time Spring dependency.
 * Graceful no-op if Spring MVC is not present.
 */
public class SpringMvcReloader {

    private final PlatformContext platformContext;

    public SpringMvcReloader(PlatformContext platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * Re-scan and re-register @RequestMapping methods for a controller class.
     */
    public boolean reloadMappings(Class<?> controllerClass) {
        boolean reloaded = false;
        // Controllers live in web application contexts — iterate all live
        // contexts and re-scan wherever this controller is registered.
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            reloaded |= reloadMappingsIn(appContext, controllerClass);
        }
        return reloaded;
    }

    private boolean reloadMappingsIn(Object appContext, Class<?> controllerClass) {
        try {
            Object handlerMapping = getBeanOfType(appContext,
                    "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping");
            if (handlerMapping == null) return false;

            String beanName = SpringBeans.findBeanName(appContext, controllerClass);
            if (beanName == null) return false;

            unregisterMappings(handlerMapping, controllerClass);

            Method detectMethod = handlerMapping.getClass().getDeclaredMethod(
                    "detectHandlerMethods", Object.class);
            detectMethod.setAccessible(true);
            detectMethod.invoke(handlerMapping, beanName);

            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            StatusReporter.warn("Spring MVC mapping re-scan failed: " + e.getMessage());
            return false;
        }
    }

    private Object getBeanOfType(Object appContext, String typeName) throws Exception {
        Class<?> targetType = Class.forName(typeName, false,
                appContext.getClass().getClassLoader());
        Method getBeanMethod = appContext.getClass().getMethod("getBean", Class.class);
        try {
            return getBeanMethod.invoke(appContext, targetType);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // NoSuchBeanDefinitionException — this context has no MVC
            // infrastructure (e.g. the Hybris global context). Not an error.
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void unregisterMappings(Object handlerMapping, Class<?> controllerClass) {
        try {
            Method getHandlerMethods = handlerMapping.getClass().getMethod("getHandlerMethods");
            Map<?, ?> handlerMethods = (Map<?, ?>) getHandlerMethods.invoke(handlerMapping);

            List<Object> toUnregister = new ArrayList<>();
            for (var entry : handlerMethods.entrySet()) {
                Object handlerMethod = entry.getValue();
                Method getBeanType = handlerMethod.getClass().getMethod("getBeanType");
                Class<?> beanType = (Class<?>) getBeanType.invoke(handlerMethod);
                if (controllerClass.equals(beanType)) {
                    toUnregister.add(entry.getKey());
                }
            }

            if (!toUnregister.isEmpty()) {
                Method unregisterMethod = handlerMapping.getClass().getMethod(
                        "unregisterMapping", Class.forName(
                                "org.springframework.web.servlet.mvc.method.RequestMappingInfo",
                                false, handlerMapping.getClass().getClassLoader()));
                for (Object mapping : toUnregister) {
                    unregisterMethod.invoke(handlerMapping, mapping);
                }
            }
        } catch (Exception e) {
            // If unregister fails, detectHandlerMethods will still work
        }
    }
}
