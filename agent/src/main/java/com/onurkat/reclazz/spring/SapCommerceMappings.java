/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;

/** Rebuilds SAP's priority selection, including the previously suppressed bean handlers. */
final class SapCommerceMappings {
    private static final String TYPE =
            "de.hybris.platform.commercewebservices.core.request.mapping.handler.CommerceHandlerMapping";
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
