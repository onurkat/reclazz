/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ui.StatusReporter;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Drives the running-system schema update through the platform's own HAC facade,
 * reflectively, so there is no compile-time dependency on SAP Commerce. It builds the
 * same configuration object the HAC "Update Running System" screen submits
 * ({@code BeautifulInitializationData}) with the init method set to UPDATE and every
 * data step turned off, then calls {@code HacInitUpdateFacade.executeInitUpdate}.
 *
 * <p>The configuration is schema-only and non-destructive: drop tables, clear HMC,
 * essential data, project data and localization are all false, so the update adds the
 * missing columns and tables for the reloaded types and touches no data. The facade
 * classes live in the {@code hac} extension's bin jar, which shares the platform
 * classloader with the application context, so the context's loader resolves them.
 * Anything unexpected (the facade is absent, an update is already running, a failure is
 * reported) returns false and the caller falls back to the manual HAC guidance.
 */
public final class HacRunningSystemUpdater implements RunningSystemUpdater {

    private static final String FACADE = "de.hybris.platform.hac.facade.HacInitUpdateFacade";
    private static final String DATA = "de.hybris.platform.hac.data.dto.BeautifulInitializationData";
    private static final String INIT_METHOD = DATA + "$InitMethod";

    private final Supplier<ClassLoader> platformLoader;

    public HacRunningSystemUpdater(Supplier<ClassLoader> platformLoader) {
        this.platformLoader = platformLoader;
    }

    @Override
    public boolean updateSchema() {
        ClassLoader loader = platformLoader == null ? null : platformLoader.get();
        if (loader == null) return false;
        // Runs on the codegen thread, which has no tenant: the platform
        // refused with "no tenant active" until this was bound first.
        if (!com.onurkat.reclazz.platform.PlatformTenant.ensureActive(loader)) {
            StatusReporter.warn("Automatic Update Running System could not bind a tenant; apply it in HAC instead.");
            return false;
        }
        try {
            Class<?> facadeClass = Class.forName(FACADE, true, loader);
            Class<?> dataClass = Class.forName(DATA, true, loader);
            Class<?> initMethodClass = Class.forName(INIT_METHOD, true, loader);

            Object facade = facadeClass.getDeclaredConstructor().newInstance();
            installQuietNotifier(facadeClass, facade, loader);
            if (Boolean.TRUE.equals(call(facade, "isLocked"))) {
                StatusReporter.warn("Update Running System is already in progress; "
                        + "skipped the automatic schema update.");
                return false;
            }

            Object data = dataClass.getDeclaredConstructor().newInstance();
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object update = Enum.valueOf((Class<Enum>) initMethodClass, "UPDATE");
            dataClass.getMethod("setInitMethod", initMethodClass).invoke(data, update);
            // Schema only, non-destructive: no drop, no data, no localization.
            setBool(dataClass, data, "setDropTables", false);
            setBool(dataClass, data, "setClearHMC", false);
            setBool(dataClass, data, "setCreateEssentialData", false);
            setBool(dataClass, data, "setCreateProjectData", false);
            setBool(dataClass, data, "setLocalizeTypes", false);

            // The update's audit step finds JAXB through the context loader.
            // On a HAC request thread that is the platform's; on the codegen
            // thread it was not, and the update failed after the schema work.
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            Object result;
            try {
                result = facadeClass.getMethod("executeInitUpdate", dataClass).invoke(facade, data);
            } finally {
                thread.setContextClassLoader(previous);
            }
            boolean ok = succeeded(result);
            if (!ok) StatusReporter.warn("Automatic Update Running System did not report success; "
                    + "apply it in HAC if the new attribute is missing.");
            return ok;
        } catch (Throwable notAvailable) {
            StatusReporter.warn("Automatic Update Running System could not run ("
                    + com.onurkat.reclazz.ui.Failures.describe(notAvailable)
                    + "); apply it in HAC instead.");
            return false;
        }
    }

    /**
     * HAC creates the facade as a Spring bean and injects a tray notifier, which
     * executeInitUpdate calls before doing anything else. Built here by reflection
     * the field is null, and the update died on that first call on every real
     * installation. A notifier that does nothing stands in for it.
     */
    private static void installQuietNotifier(Class<?> facadeClass, Object facade, ClassLoader loader) throws Exception {
        Class<?> notifierType;
        try {
            notifierType = Class.forName("de.hybris.bootstrap.osnotify.SystemTrayNotifier", false, loader);
        } catch (ClassNotFoundException absent) {
            return;
        }
        if (!notifierType.isInterface()) return;
        java.lang.reflect.Method setter;
        try {
            setter = facadeClass.getMethod("setNotifier", notifierType);
        } catch (NoSuchMethodException absent) {
            return;
        }
        Object quiet = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{notifierType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Reclazz quiet notifier";
                    default -> null;
                });
        setter.invoke(facade, quiet);
    }

    /**
     * Read the facade's result map. A completed update reports no failure; the map also
     * carries a success flag on the versions that set one. A result that is not a map, or
     * that flags a failure, is not a success.
     */
    static boolean succeeded(Object result) {
        if (!(result instanceof Map<?, ?> map)) return false;
        Object failed = map.get("Failed");
        boolean isFailure = (failed instanceof Boolean b && b)
                || (failed instanceof String s && !s.isBlank() && !"false".equalsIgnoreCase(s));
        if (isFailure) return false;
        Object success = map.get("success");
        if (success instanceof Boolean b) return b;
        if (success instanceof String s) return Boolean.parseBoolean(s);
        // No failure flagged and no explicit success flag: a returned result is complete.
        return true;
    }

    private static void setBool(Class<?> cls, Object target, String setter, boolean value) throws Exception {
        try {
            cls.getMethod(setter, Boolean.class).invoke(target, Boolean.valueOf(value));
        } catch (NoSuchMethodException primitive) {
            cls.getMethod(setter, boolean.class).invoke(target, value);
        }
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
}
