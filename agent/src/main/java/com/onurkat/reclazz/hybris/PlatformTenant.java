/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import java.lang.reflect.Method;

/**
 * Binds the calling thread to the master tenant.
 *
 * Most of the platform's static API reads the tenant from a ThreadLocal that
 * request and cronjob threads get for free. Reclazz calls from a file-watcher
 * thread, which has none, and the failure is not a clear "no tenant" error: it
 * surfaces as a NullPointerException from somewhere deep in the call, with no
 * message. Anything this agent calls on the platform goes through here first.
 *
 * Reflection, and through the platform's own classloader: the agent sits on the
 * system classpath and cannot see the platform's classes from there.
 */
public final class PlatformTenant {

    private static final String REGISTRY = "de.hybris.platform.core.Registry";

    private PlatformTenant() {
    }

    /**
     * @return true when the thread has a tenant afterwards, false when the
     *         platform is not reachable through this loader
     */
    public static boolean ensureActive(ClassLoader platformClassLoader) {
        if (platformClassLoader == null) return false;
        try {
            Class<?> registry = Class.forName(REGISTRY, false, platformClassLoader);
            Method hasCurrentTenant = registry.getMethod("hasCurrentTenant");
            if (!(Boolean) hasCurrentTenant.invoke(null)) {
                registry.getMethod("activateMasterTenant").invoke(null);
            }
            return true;
        } catch (Throwable t) {
            // Not on a platform, or a platform that does not work this way.
            // Callers treat false as "do nothing", which is the right answer
            // for a Spring Boot application that happens to save a file.
            return false;
        }
    }
}
