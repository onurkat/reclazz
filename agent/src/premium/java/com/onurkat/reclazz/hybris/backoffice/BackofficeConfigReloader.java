/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.Plural;
import com.onurkat.reclazz.ui.StatusReporter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Runs the native F4 cockpit-configuration reset after a module XML save.
 * This rebuilds persisted configuration from the modules and resets UI-made
 * configuration too. It does not reset widgets or delete media directly.
 */
public final class BackofficeConfigReloader {
    private static final String SERVICE_TYPE = "com.hybris.cockpitng.core.config.CockpitConfigurationService";
    private BackofficeConfigReloader() { }

    /** The factory supplies the existing verified owner tenant/session boundary. */
    @FunctionalInterface
    public interface OwnerScope {
        Object execute(Object context, Callable<Object> action) throws Exception;
    }

    /** Distinguishes absence, failure, opt-out and a completed native operation. */
    public record Result(int contexts, int services, int failures, boolean disabled) { }

    public static Result reload(String fileName, List<Object> contexts, boolean enabled, OwnerScope scope) {
        if (!enabled) {
            StatusReporter.warn(fileName + " saved, not applied; press F4 and reset cockpit configuration in Backoffice"
                    + " (backofficeConfigReset=false).");
            return new Result(0, 0, 0, true);
        }
        int found = 0, reset = 0, failures = 0;
        Set<Object> completed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object context : contexts) {
            Class<?> type;
            try {
                ClassLoader loader = context.getClass().getClassLoader();
                try {
                    Object actual = context.getClass().getMethod("getClassLoader").invoke(context);
                    if (actual instanceof ClassLoader contextLoader) loader = contextLoader;
                } catch (NoSuchMethodException ignored) { /* An opaque context may expose only its implementation loader. */ }
                type = Class.forName(SERVICE_TYPE, false, loader);
            } catch (ClassNotFoundException absent) {
                continue;
            } catch (Throwable failure) {
                failures++;
                failed(fileName, context, failure);
                continue;
            }
            found++;
            try {
                Object result = scope.execute(context, () -> {
                    // F4 addresses the active alias, not its unused lazy parent definitions.
                    Object service = context.getClass().getMethod("getBean", String.class)
                            .invoke(context, "cockpitConfigurationService");
                    if (service == null || !type.isInstance(service)) {
                        throw new IllegalStateException("cockpitConfigurationService has an incompatible type");
                    }
                    if (completed.contains(service)) return null;
                    type.getMethod("resetToDefaults").invoke(service);
                    return service;
                });
                if (result != null) {
                    completed.add(result);
                    reset++;
                }
            } catch (Throwable failure) {
                failures++;
                failed(fileName, context, failure);
            }
        }
        if (reset > 0) {
            StatusReporter.success("Backoffice configuration reset as with F4 (" + Plural.of(reset, "service")
                    + ") for " + fileName + ": open a new view to see it. Configuration changed in the UI was reset too.");
        }
        return new Result(found, reset, failures, false);
    }

    private static void failed(String fileName, Object context, Throwable failure) {
        String display = context == null ? "null" : context.getClass().getName();
        try { if (context != null) display = String.valueOf(context.getClass().getMethod("getDisplayName").invoke(context)); }
        catch (ReflectiveOperationException ignored) { /* Class name remains useful for an unavailable context. */ }
        StatusReporter.warn(fileName + ": Backoffice configuration reset failed in " + display + ": "
                + Failures.describe(failure));
    }
}
