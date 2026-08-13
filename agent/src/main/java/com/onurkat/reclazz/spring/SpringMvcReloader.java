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
        int contexts = 0;
        // Controllers live in web application contexts — iterate all live
        // contexts and re-scan wherever this controller is registered.
        for (Object appContext : platformContext.getAllApplicationContexts()) {
            contexts++;
            reloaded |= reloadMappingsIn(appContext, controllerClass);
        }


        if (!reloaded) {
            // Reaching here used to produce no output at all, so a mapping
            // that silently kept its old value looked identical to a reload
            // that had simply not been asked for.
            StatusReporter.warn("MVC mappings not re-scanned for " + controllerClass.getName()
                    + ": searched " + contexts + " application context(s) and none of them "
                    + (contexts == 0 ? "were captured" : "held it as a handler"));
        }
        return reloaded;
    }

    private boolean reloadMappingsIn(Object appContext, Class<?> controllerClass) {
        try {
            // Find the bean first. Asking for the handler mapping up front and
            // returning early when it is absent meant the one context that
            // actually owns the controller was never examined: in Hybris the
            // bean sits in the DispatcherServlet's own context while the
            // registry resolves from its parent, so the pair is never both
            // present in the context being looked at.
            String beanName = SpringBeans.findBeanName(appContext, controllerClass);
            if (beanName == null) {
                String[] byName = SpringBeans.beanNamesForType(appContext, controllerClass.getName());
                beanName = byName.length > 0 ? byName[0] : null;
            }
            if (beanName == null) return false;

            // The controller is here, so this is its web app. The registry is
            // in this context or in one of its parents.
            Object handlerMapping = findHandlerMapping(appContext);
            if (handlerMapping == null) {
                StatusReporter.warn("MVC re-scan skipped for " + controllerClass.getName()
                        + ": found the bean as '" + beanName + "' but no RequestMappingHandlerMapping "
                        + "in that context or its parents.");
                return false;
            }

            unregisterMappings(handlerMapping, controllerClass);

            // detectHandlerMethods is declared on AbstractHandlerMethodMapping,
            // not on RequestMappingHandlerMapping, and getDeclaredMethod does
            // not look at supertypes. Asking the concrete class for it threw
            // NoSuchMethodException on every single re-scan, which the catch
            // below reported and then swallowed as a returned false.
            Method detectMethod = findMethodInHierarchy(
                    handlerMapping.getClass(), "detectHandlerMethods", Object.class);
            if (detectMethod == null) {
                StatusReporter.warn("MVC re-scan cannot proceed for " + controllerClass.getName()
                        + ": no detectHandlerMethods on " + handlerMapping.getClass().getName()
                        + " or its supertypes");
                return false;
            }
            // Spring caches reflection per Class, and redefineClasses leaves
            // the Class identity alone, so those caches keep handing out the
            // Method objects read at startup with the annotations they had
            // then. The re-scan would faithfully re-register the old mapping.
            clearSpringReflectionCaches(handlerMapping.getClass().getClassLoader());

            detectMethod.setAccessible(true);
            detectMethod.invoke(handlerMapping, beanName);

            return true;
        } catch (Exception e) {
            StatusReporter.warn("Spring MVC mapping re-scan failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Drops Spring's own reflection and annotation caches so a re-scan reads
     * the class as it is now rather than as it was at startup. Both are
     * public no-arg statics that Spring provides for exactly this.
     */
    private void clearSpringReflectionCaches(ClassLoader loader) {
        String[] holders = {
                "org.springframework.util.ReflectionUtils",
                "org.springframework.core.annotation.AnnotationUtils",
        };
        for (String holder : holders) {
            try {
                Class.forName(holder, false, loader).getMethod("clearCache").invoke(null);
            } catch (Exception e) {
                // An older Spring without the hook, or a context that cannot
                // see it. Worth knowing about, because without the clear the
                // re-scan below is very likely a no-op.
                StatusReporter.warn("Could not clear " + holder
                        + " (" + e.getClass().getSimpleName() + "); a stale mapping may survive");
            }
        }
    }

    /** Looks up a method on a type or any of its supertypes. */
    private static Method findMethodInHierarchy(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // keep walking
            }
        }
        return null;
    }

    /**
     * The registry for a controller's context, which may be declared in a
     * parent: a DispatcherServlet context inherits the root web context, and
     * in Hybris that is where the two end up living apart.
     */
    private Object findHandlerMapping(Object appContext) {
        Object ctx = appContext;
        for (int depth = 0; ctx != null && depth < 5; depth++) {
            try {
                Object mapping = getBeanOfType(ctx,
                        "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping");
                if (mapping != null) return mapping;
            } catch (Exception ignored) {}
            try {
                ctx = ctx.getClass().getMethod("getParent").invoke(ctx);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Best-effort identity of a context, for diagnostics. */
    private String describe(Object ctx) {
        for (String getter : new String[]{"getDisplayName", "getId", "getApplicationName"}) {
            try {
                Object v = ctx.getClass().getMethod(getter).invoke(ctx);
                if (v != null && !v.toString().isBlank()) return v.toString();
            } catch (Exception ignored) {}
        }
        return ctx.getClass().getSimpleName();
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
                // By name, not identity. The registration holds the Class the
                // web context loaded, and the reload hands us the one it found;
                // for the same controller those can be two objects. Comparing
                // them with equals matched nothing, so nothing was ever
                // unregistered and the re-scan added a second mapping for the
                // same path, which Spring then refuses to serve at all:
                // "Ambiguous handler methods mapped for ...", an HTTP 500 where
                // there had been a working endpoint.
                if (beanType != null && beanType.getName().equals(controllerClass.getName())) {
                    toUnregister.add(entry.getKey());
                }
            }

            if (!toUnregister.isEmpty()) {
                // unregisterMapping is declared on AbstractHandlerMethodMapping
                // as unregisterMapping(T), so after erasure its parameter is
                // Object, not RequestMappingInfo. Asking for the concrete type
                // threw NoSuchMethodException on every call, and the catch
                // below used to swallow it in silence.
                Method unregisterMethod = findMethodInHierarchy(
                        handlerMapping.getClass(), "unregisterMapping", Object.class);
                if (unregisterMethod == null) {
                    StatusReporter.warn("MVC unregister unavailable on "
                            + handlerMapping.getClass().getName()
                            + "; skipping re-scan to avoid duplicate mappings");
                    return;
                }
                unregisterMethod.setAccessible(true);
                for (Object mapping : toUnregister) {
                    unregisterMethod.invoke(handlerMapping, mapping);
                }
            }
        } catch (Exception e) {
            // Not harmless, which the previous comment here claimed: leaving
            // the old mappings in place while the re-scan adds new ones is
            // what produces the ambiguous-mapping 500.
            StatusReporter.warn("MVC unregister failed for " + controllerClass.getName()
                    + " (" + e + "); the re-scan may leave duplicate mappings");
        }
    }
}
