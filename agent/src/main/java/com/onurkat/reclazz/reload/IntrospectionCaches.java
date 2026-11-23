/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * R35: other per-class member caches a reload that changes members must drop, each through the library's own API where
 * it has one:
 * <ul>
 *   <li>Velocity's IntrospectorCache (email and CMS templates, ImpEx VelocityTranslator): {@code clear()};</li>
 *   <li>commons-beanutils 1 and 2 PropertyUtilsBean: {@code clearDescriptors()};</li>
 *   <li>SAP Commerce ConversionHelper's static {@code propertyDescriptorCache} (DTO copyProperties).</li>
 * </ul>
 */
final class IntrospectionCaches {
    private IntrospectionCaches() { }

    static void flush(Class<?> owner) {
        call("velocity", "clear");
        call("beanutils", "clearDescriptors");
        try {
            Class<?> helper = Class.forName("de.hybris.platform.servicelayer.dto.converter.ConversionHelper", false, owner.getClassLoader());
            Field cache = helper.getDeclaredField("propertyDescriptorCache");
            cache.setAccessible(true);
            if (cache.get(null) instanceof Map<?, ?> map) map.clear();
        } catch (ClassNotFoundException | LinkageError notSapCommerce) {
            // Not an SAP Commerce class loader.
        } catch (Throwable failure) {
            String reason = "SAP Commerce ConversionHelper's property cache could not be cleared ("
                    + failure.getClass().getSimpleName() + "); a restart shows added DTO properties to copyProperties";
            com.onurkat.reclazz.ui.StatusReporter.warn(reason);
            com.onurkat.reclazz.ui.RestartLedger.note("SAP Commerce ConversionHelper", reason);
        }
    }

    private static void call(String kind, String method) {
        for (Object cache : com.onurkat.reclazz.bootstrap.TrackedInstances.live(kind)) {
            try {
                cache.getClass().getMethod(method).invoke(cache);
            } catch (Throwable failure) {
                String reason = cache.getClass().getName() + "." + method + "() failed ("
                        + failure.getClass().getSimpleName() + "); a restart shows added members to it";
                com.onurkat.reclazz.ui.StatusReporter.warn(reason);
                com.onurkat.reclazz.ui.RestartLedger.note(cache.getClass().getName(), reason);
            }
        }
    }
}
