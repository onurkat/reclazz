/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformTenant;
import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;

/** Rebuilds SAP's priority selection, including the previously suppressed bean handlers. */
public final class SapCommerceMappings {
    private static final String TYPE =
            "de.hybris.platform.commercewebservices.core.request.mapping.handler.CommerceHandlerMapping";
    private static final String OVERRIDE =
            "de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride";
    // A suppressed synthetic handler is absent from Spring's registry too. Remember the
    // attempt, not just registered adapters, until this context is replaced/restarted.
    private static final Set<Object> ADDED_HANDLERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final String RESTART = "SAP OCC mappings need a restart";

    private SapCommerceMappings() { }

    static boolean supports(Object mapping) {
        return sdkType(mapping) != null;
    }

    private static Class<?> sdkType(Object mapping) {
        for (Class<?> c = mapping.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(TYPE)) return c;
        }
        return null;
    }

    static boolean addedHandlers(Object mapping, Class<?> controller) {
        ADDED_HANDLERS.add(mapping);
        return refused(mapping, controller, "synthetic added endpoints cannot participate in SDK priority selection");
    }

    @SuppressWarnings("unchecked")
    static boolean rescan(Object mapping, Class<?> controller) {
        Lock lock = SpringMvcReloader.registryWriteLock(mapping);
        if (lock == null) return refused(mapping, controller, "mapping registry lock unavailable; previous mappings retained");
        // The registry reads each handler's priority from the platform
        // configuration, which answers "no tenant active" on the reload thread.
        PlatformTenant.ensureActive(mapping.getClass().getClassLoader());
        lock.lock();
        try {
            if (ADDED_HANDLERS.contains(mapping)) {
                return refused(mapping, controller, "this context has synthetic added endpoints; previous mappings retained");
            }
            Method handlers = mapping.getClass().getMethod("getHandlerMethods");
            Method unregister = Reflect.findMethod(mapping.getClass(), "unregisterMapping", Object.class);
            Method register = Reflect.findMethod(mapping.getClass(), "registerMapping",
                    Object.class, Object.class, Method.class);
            Method initialize = Reflect.findMethod(mapping.getClass(), "initHandlerMethods");
            if (unregister == null || register == null || initialize == null) {
                return refused(mapping, controller, "SDK mapping lifecycle unavailable; previous mappings retained");
            }
            unregister.setAccessible(true);
            register.setAccessible(true);
            initialize.setAccessible(true);
            var field = sdkType(mapping).getDeclaredField("overriddenRequestMapping");
            field.setAccessible(true);
            Map<Object, Object> priorities = (Map<Object, Object>) field.get(mapping);
            Map<Object, Object> oldPriorities = new LinkedHashMap<>(priorities);
            Map<?, ?> current = (Map<?, ?>) handlers.invoke(mapping);
            var saved = new ArrayList<Registration>();
            for (var entry : current.entrySet()) {
                Object handler = entry.getValue();
                Object bean = handler.getClass().getMethod("getBean").invoke(handler);
                Method method = (Method) handler.getClass().getMethod("getMethod").invoke(handler);
                if (!(bean instanceof String) || method.getDeclaringClass().getName().contains(SpringMvcReloader.ADAPTER_SUFFIX)) {
                    return refused(mapping, controller, "instance or synthetic registrations cannot be rediscovered; previous mappings retained");
                }
                saved.add(new Registration(entry.getKey(), bean, method));
            }
            // Resolve all reflection hooks before removing the first mapping.
            var clears = new ArrayList<Method>();
            for (String name : new String[]{"org.springframework.util.ReflectionUtils",
                    "org.springframework.core.annotation.AnnotationUtils"}) {
                clears.add(Class.forName(name, false, mapping.getClass().getClassLoader()).getMethod("clearCache"));
            }
            for (Method clear : clears) clear.invoke(null);
            try {
                for (Registration old : saved) unregister.invoke(mapping, old.mapping());
                priorities.clear();
                initialize.invoke(mapping);
            } catch (Exception failed) {
                try {
                    for (Object key : new ArrayList<>(((Map<?, ?>) handlers.invoke(mapping)).keySet())) {
                        unregister.invoke(mapping, key);
                    }
                    priorities.clear();
                    priorities.putAll(oldPriorities);
                    for (Registration old : saved) register.invoke(mapping, old.mapping(), old.bean(), old.method());
                } catch (Exception rollback) {
                    failed.addSuppressed(rollback);
                    return refused(mapping, controller, "registry recovery failed: " + Failures.describe(failed)
                            + "; recovery: " + Failures.describe(rollback));
                }
                return refused(mapping, controller, "previous mappings restored after rebuild failure: " + Failures.describe(failed));
            }
            RestartLedger.resolve(subject(mapping, controller), RESTART);
            return true;
        } catch (Exception failed) {
            return refused(mapping, controller, "mapping inspection failed: " + Failures.describe(failed));
        } finally {
            lock.unlock();
        }
    }

    /**
     * The registry reads each {@code @RequestMappingOverride} priority from the
     * configuration once, when it is built, and drops the handlers that lose.
     * A changed priority property was applied to the configuration and changed
     * nothing: the handler that lost at startup kept losing. Every registry
     * with a handler whose priority comes from one of the changed keys is
     * rebuilt, which reads the priorities again.
     *
     * @return how many registries were rebuilt
     */
    public static int reselect(Collection<String> changedKeys) {
        if (changedKeys == null || changedKeys.isEmpty()) return 0;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int rebuilt = 0;
        for (Object context : ApplicationContextHolder.getAllContexts()) {
            for (Object mapping : mappings(context)) {
                if (!seen.add(mapping)) continue;
                Class<?> controller = controllerUsing(mapping, changedKeys);
                if (controller == null) continue;
                if (rescan(mapping, controller)) {
                    StatusReporter.success("OCC handler priorities read again for " + controller.getName());
                    rebuilt++;
                }
            }
        }
        return rebuilt;
    }

    private static List<Object> mappings(Object context) {
        Class<?> type;
        try {
            // The loader fixed when the context was refreshed. The context's own
            // getClassLoader falls back to the calling thread's, which on the
            // reload thread is the agent's and cannot see OCC.
            Object factory = context.getClass().getMethod("getBeanFactory").invoke(context);
            ClassLoader loader = (ClassLoader) factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
            type = Class.forName(TYPE, false, loader);
        } catch (Throwable notOcc) {
            return List.of();
        }
        try {
            // Existing singletons only. Allowing eager initialisation creates
            // lazy factory beans to learn their type, and on the reload thread
            // a request- or tenant-scoped one throws: measured on SAP Commerce,
            // where that left every priority change unapplied without a word.
            Map<?, ?> beans = (Map<?, ?>) context.getClass()
                    .getMethod("getBeansOfType", Class.class, boolean.class, boolean.class)
                    .invoke(context, type, false, false);
            return new ArrayList<>(beans.values());
        } catch (Throwable unreadable) {
            StatusReporter.warn("The OCC handler registry could not be read after a priority property changed ("
                    + Failures.describe(unreadable) + "); the previous selection stays until a restart.");
            RestartLedger.note("OCC handler priorities", "not read again after a priority property changed");
            return List.of();
        }
    }

    /**
     * The first handler type whose priority is read from one of the keys, the
     * way the registry names it. On OCC the registry sits in the dispatcher's
     * context and the controllers in its parent, so the parents are searched.
     */
    @SuppressWarnings("unchecked")
    private static Class<?> controllerUsing(Object mapping, Collection<String> keys) {
        String[] names;
        Object context;
        Method find, userClass, isHandler, getType, priorityProperty;
        Class<? extends Annotation> override;
        try {
            ClassLoader loader = mapping.getClass().getClassLoader();
            override = (Class<? extends Annotation>) Class.forName(OVERRIDE, false, loader);
            priorityProperty = override.getMethod("priorityProperty");
            find = Class.forName("org.springframework.core.annotation.AnnotationUtils", false, loader)
                    .getMethod("findAnnotation", Method.class, Class.class);
            userClass = Class.forName("org.springframework.util.ClassUtils", false, loader)
                    .getMethod("getUserClass", Class.class);
            isHandler = Reflect.findMethod(mapping.getClass(), "isHandler", Class.class);
            context = mapping.getClass().getMethod("getApplicationContext").invoke(mapping);
            if (isHandler == null || context == null) return null;
            isHandler.setAccessible(true);
            getType = context.getClass().getMethod("getType", String.class, boolean.class);
            Class<?> listable = Class.forName("org.springframework.beans.factory.ListableBeanFactory", false, loader);
            names = (String[]) Class.forName("org.springframework.beans.factory.BeanFactoryUtils", false, loader)
                    .getMethod("beanNamesForTypeIncludingAncestors", listable, Class.class)
                    .invoke(null, context, Object.class);
        } catch (Throwable unreadable) {
            StatusReporter.warn("A changed OCC priority could not be matched to its handlers ("
                    + Failures.describe(unreadable) + "); the previous selection stays until a restart.");
            RestartLedger.note("OCC handler priorities", "not read again after a priority property changed");
            return null;
        }
        for (String name : names) {
            try {
                Class<?> type = (Class<?>) getType.invoke(context, name, false);
                if (type == null || !(Boolean) isHandler.invoke(mapping, type)) continue;
                Class<?> user = (Class<?>) userClass.invoke(null, type);
                for (Class<?> c = user; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method method : c.getDeclaredMethods()) {
                        Annotation found = (Annotation) find.invoke(null, method, override);
                        if (found == null) continue;
                        String key = (String) priorityProperty.invoke(found);
                        if (key.isEmpty()) {
                            key = "requestMappingOverride." + method.getDeclaringClass().getName()
                                    + "." + method.getName() + ".priority";
                        }
                        if (keys.contains(key)) return user;
                    }
                }
            } catch (Throwable unresolvable) {
                // One definition whose type cannot be resolved; it is not a handler the registry has.
            }
        }
        return null;
    }

    private static String subject(Object mapping, Class<?> controller) {
        // One web context recovering must not clear another context's concern.
        return controller.getName() + " (OCC registry " + Integer.toHexString(System.identityHashCode(mapping)) + ")";
    }

    private static boolean refused(Object mapping, Class<?> controller, String detail) {
        StatusReporter.warn(RESTART + " for " + controller.getName() + ": " + detail);
        RestartLedger.note(subject(mapping, controller), RESTART);
        return false;
    }

    private record Registration(Object mapping, Object bean, Method method) { }
}
