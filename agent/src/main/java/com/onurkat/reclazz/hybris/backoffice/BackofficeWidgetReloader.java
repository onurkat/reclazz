/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Method;

/**
 * Reloads SAP Commerce Backoffice widget definitions after structural reload.
 * When a widget controller gains new @SocketEvent actions via structural reload,
 * the widget registry doesn't know about them until this class triggers a refresh.
 *
 * All interaction is via reflection — no compile-time Backoffice dependency.
 * Graceful no-op if Backoffice is not present.
 */
public class BackofficeWidgetReloader {

    /**
     * Reload a widget's definition in the Backoffice widget registry.
     *
     * @param widgetClass the reloaded widget controller class
     * @return true if the widget was successfully reloaded
     */
    public static boolean reloadWidget(Class<?> widgetClass) {
        try {
            // Get ApplicationContext from Hybris Registry
            Object appContext = getApplicationContext();
            if (appContext == null) return false;

            // Try to get CockpitWidgetRegistry
            Object widgetRegistry = getWidgetRegistry(appContext);
            if (widgetRegistry == null) return false;

            // Try refresh() on the widget registry
            try {
                Method refreshMethod = widgetRegistry.getClass().getMethod("refresh");
                refreshMethod.invoke(widgetRegistry);
                return true;
            } catch (NoSuchMethodException e) {
                // Try alternative: re-register specific widget
                return reregisterWidget(appContext, widgetRegistry, widgetClass);
            }
        } catch (ClassNotFoundException e) {
            // Backoffice not on classpath — no-op
            return false;
        } catch (Exception e) {
            StatusReporter.warn("Backoffice widget reload failed: " + com.onurkat.reclazz.ui.Failures.describe(e));
            return false;
        }
    }

    private static Object getApplicationContext() throws Exception {
        try {
            Class<?> registryClass = Class.forName("de.hybris.platform.core.Registry");
            Method getCtx = registryClass.getMethod("getApplicationContext");
            return getCtx.invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object getWidgetRegistry(Object appContext) throws ClassNotFoundException {
        try {
            // Try CockpitWidgetRegistry first
            Class<?> registryType = Class.forName(
                    "com.hybris.cockpitng.core.widget.WidgetDefinitionRegistry",
                    false, appContext.getClass().getClassLoader());
            Method getBean = appContext.getClass().getMethod("getBean", Class.class);
            return getBean.invoke(appContext, registryType);
        } catch (ClassNotFoundException e) {
            throw e; // Backoffice not present
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attempt to re-register a specific widget by triggering a context refresh
     * on the widget's component. Falls back to a broader approach if needed.
     */
    private static boolean reregisterWidget(Object appContext, Object widgetRegistry,
                                             Class<?> widgetClass) {
        try {
            // Try to find the widget definition by scanning the registry
            // for a definition that references this controller class
            Method getDefinitions = widgetRegistry.getClass().getMethod("getWidgetDefinitions");
            Object definitions = getDefinitions.invoke(widgetRegistry);
            if (definitions instanceof Iterable<?> defs) {
                for (Object def : defs) {
                    try {
                        Method getControllerClass = def.getClass().getMethod("getControllerClass");
                        Object controllerClassName = getControllerClass.invoke(def);
                        if (widgetClass.getName().equals(String.valueOf(controllerClassName))) {
                            // Found the widget definition — trigger reload
                            Method reloadDef = def.getClass().getMethod("reload");
                            reloadDef.invoke(def);
                            return true;
                        }
                    } catch (NoSuchMethodException ignored) {
                        // Different API version, try next approach
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }
}
