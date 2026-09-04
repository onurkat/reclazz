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
                    + ": searched " + com.onurkat.reclazz.ui.Plural.of(contexts, "application context")
                    + " and none of them "
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
            Method detectMethod = com.onurkat.reclazz.util.Reflect.findMethod(
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
            StatusReporter.warn("Spring MVC mapping re-scan failed: " + com.onurkat.reclazz.ui.Failures.describe(e));
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
    /**
     * Registers a class carrying copies of the handler methods this reload
     * added, so Spring's scan can see what reflection on the controller cannot.
     * See AddedEndpointAdapter.
     *
     * @return true when the mappings were registered
     */
    /**
     * The subset of {@code addedMethodSigs} that carry an HTTP mapping
     * annotation in the new bytecode, {@code @GetMapping} through
     * {@code @RequestMapping}. Deciding by annotation rather than by "was a
     * method added" is what keeps a private helper, or the {@code lambda$}
     * synthetics an edited body brings along, from being reported as a
     * handler that needs a restart.
     */
    static java.util.Set<String> mappedMethodsAmong(java.util.Set<String> addedMethodSigs,
                                                    byte[] newBytecode) {
        if (addedMethodSigs == null || addedMethodSigs.isEmpty() || newBytecode == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> mapped = new java.util.LinkedHashSet<>();
        try {
            new org.objectweb.asm.ClassReader(newBytecode).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.MethodVisitor visitMethod(
                                int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            String key = name + ":" + descriptor;
                            if (!addedMethodSigs.contains(key)) return null;
                            return new org.objectweb.asm.MethodVisitor(
                                    org.objectweb.asm.Opcodes.ASM9) {
                                @Override
                                public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                        String annotationDesc, boolean visible) {
                                    if (annotationDesc.startsWith(
                                            "Lorg/springframework/web/bind/annotation/")
                                            && annotationDesc.endsWith("Mapping;")) {
                                        mapped.add(key);
                                    }
                                    return null;
                                }
                            };
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_CODE);
        } catch (Throwable unreadable) {
            // A class this cannot read is a class whose handlers cannot be
            // adapted either; the caller's mapped/failed reporting covers it.
            return addedMethodSigs;
        }
        return mapped;
    }

    public boolean registerAddedEndpoints(Class<?> controllerClass,
                                          java.util.Set<String> addedMethods,
                                          byte[] newBytecode) {
        if (addedMethods == null || addedMethods.isEmpty() || newBytecode == null) return false;
        try {
            // The same search the re-scan makes: the controller decides which
            // context this is, and the registry may live in a parent.
            Object appContext = null;
            String beanName = null;
            for (Object candidate : platformContext.getAllApplicationContexts()) {
                String name = SpringBeans.findBeanName(candidate, controllerClass);
                if (name == null) {
                    String[] byType = SpringBeans.beanNamesForType(candidate, controllerClass.getName());
                    name = byType.length > 0 ? byType[0] : null;
                }
                if (name != null) {
                    appContext = candidate;
                    beanName = name;
                    break;
                }
            }
            if (appContext == null) return false;

            Object controllerBean = appContext.getClass()
                    .getMethod("getBean", String.class).invoke(appContext, beanName);

            Object handlerMapping = findHandlerMapping(appContext);
            if (handlerMapping == null) return false;

            // The previous stand-in still holds the paths added before this
            // reload, and this one carries them again. The controller's own
            // mappings were re-registered a moment ago and must stay.
            unregisterMappings(handlerMapping, controllerClass, false);

            Object adapter = AddedEndpointAdapter.create(controllerClass, controllerBean,
                    newBytecode, addedMethods, ADAPTER_VERSION.incrementAndGet());
            if (adapter == null) return false;

            Method detect = com.onurkat.reclazz.util.Reflect.findMethod(
                    handlerMapping.getClass(), "detectHandlerMethods", Object.class);
            if (detect == null) return false;
            detect.setAccessible(true);
            detect.invoke(handlerMapping, adapter);
            return true;
        } catch (Throwable t) {
            StatusReporter.warn("Could not map the handler methods added to "
                    + controllerClass.getName() + ": " + rootCause(t));
            return false;
        }
    }

    /** Names the classes that stand in for added handler methods. */
    static final String ADAPTER_SUFFIX = "$$ReclazzEndpoints$v";

    private static final java.util.concurrent.atomic.AtomicInteger ADAPTER_VERSION =
            new java.util.concurrent.atomic.AtomicInteger();

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    private void unregisterMappings(Object handlerMapping, Class<?> controllerClass) {
        unregisterMappings(handlerMapping, controllerClass, true);
    }

    /**
     * @param includeTheControllerItself false to take out only the stand-ins for
     *        previously added handler methods. The re-scan has just registered
     *        the controller's own mappings by then, and taking those out again
     *        leaves the application with no endpoints at all.
     */
    private void unregisterMappings(Object handlerMapping, Class<?> controllerClass,
                                    boolean includeTheControllerItself) {
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
                if (beanType == null) continue;

                // The controller's own mappings, and the ones standing in for
                // the methods a previous reload added. Those live on a
                // generated class, and leaving them registered makes the next
                // reload's copy a duplicate: Spring refuses the pair as an
                // ambiguous mapping and the endpoint stops working.
                boolean itsOwn = beanType.getName().equals(controllerClass.getName());
                boolean standIn = beanType.getName().startsWith(
                        controllerClass.getName() + ADAPTER_SUFFIX);
                if ((itsOwn && includeTheControllerItself) || standIn) {
                    toUnregister.add(entry.getKey());
                }
            }

            if (!toUnregister.isEmpty()) {
                // unregisterMapping is declared on AbstractHandlerMethodMapping
                // as unregisterMapping(T), so after erasure its parameter is
                // Object, not RequestMappingInfo. Asking for the concrete type
                // threw NoSuchMethodException on every call, and the catch
                // below used to swallow it in silence.
                Method unregisterMethod = com.onurkat.reclazz.util.Reflect.findMethod(
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
