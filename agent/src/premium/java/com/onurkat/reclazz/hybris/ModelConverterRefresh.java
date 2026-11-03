/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * Brings the service layer's model converters up to date with a type system
 * the running-system update just changed.
 *
 * <p>The converter registry is filled once, when the tenant starts, and each
 * converter reads its type's attributes once, on first use. After an update
 * that added an attribute, {@code ModelService} still answered
 * "cannot find attribute" for it; after one that added a type, the type had
 * no converter of its own and resolved to its supertype's, so it was created
 * as a plain {@code ItemModel}.
 *
 * <p>Both have a platform answer, and this calls them: every converter that
 * declares itself type-system aware is told the type system changed, which
 * makes it read its attributes again on next use, and the registry loads its
 * defaults again, which adds a converter for each type it does not have yet
 * and leaves the ones it has in place.
 */
public final class ModelConverterRefresh {

    private static final String REGISTRY = "de.hybris.platform.servicelayer.internal.converter.ConverterRegistry";
    private static final String TYPE_SYSTEM_AWARE =
            "de.hybris.platform.servicelayer.internal.converter.impl.TypeSystemAwareModelConverter";

    private ModelConverterRefresh() {
    }

    /** @return true when the registry was found and refreshed */
    public static boolean refresh(ClassLoader platformLoader) {
        try {
            Class<?> registryType = Class.forName(REGISTRY, false, platformLoader);
            Object context = Class.forName("de.hybris.platform.core.Registry", true, platformLoader)
                    .getMethod("getApplicationContext").invoke(null);
            if (context == null) return false;
            Map<?, ?> registries = (Map<?, ?>) context.getClass()
                    .getMethod("getBeansOfType", Class.class).invoke(context, registryType);
            if (registries.isEmpty()) return false;
            Class<?> aware = Class.forName(TYPE_SYSTEM_AWARE, false, platformLoader);
            Method changed = aware.getMethod("typeSystemChanged");
            for (Object registry : registries.values()) {
                refreshOne(registry, registryType, aware, changed);
            }
            return true;
        } catch (Throwable unavailable) {
            StatusReporter.warn("The model converters could not be refreshed after Update Running System ("
                    + Failures.describe(unavailable) + "); a new type or attribute may not be reachable "
                    + "through ModelService until a restart.");
            RestartLedger.note("model converters", "not refreshed after Update Running System");
            return false;
        }
    }

    private static void refreshOne(Object registry, Class<?> registryType, Class<?> aware, Method changed)
            throws ReflectiveOperationException {
        Collection<?> converters = (Collection<?>) registryType.getMethod("getModelConverters").invoke(registry);
        for (Object converter : converters) {
            if (aware.isInstance(converter)) changed.invoke(converter);
        }
        Method loadDefaults = find(registry.getClass(), "loadDefaults", boolean.class);
        if (loadDefaults != null) {
            loadDefaults.setAccessible(true);
            loadDefaults.invoke(registry, true);
        }
        // A type the registry did not know resolved to its supertype's converter,
        // and that answer is cached by type code; it would outlive the new converter.
        clearMap(registry, "typeToConverterCache");
        clearMap(registry, "modelToConverterCache");
    }

    private static Method find(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException notHere) {
                // Declared further up.
            }
        }
        return null;
    }

    private static void clearMap(Object owner, String name) throws IllegalAccessException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            Field field;
            try {
                field = c.getDeclaredField(name);
            } catch (NoSuchFieldException notHere) {
                continue;
            }
            field.setAccessible(true);
            if (field.get(owner) instanceof Map<?, ?> map) map.clear();
            return;
        }
    }
}
