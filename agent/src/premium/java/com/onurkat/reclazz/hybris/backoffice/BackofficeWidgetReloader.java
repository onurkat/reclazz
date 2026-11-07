/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.platform.BackofficeSocketEventAdvice;
import java.util.List;
import com.onurkat.reclazz.ext.StructuralReloadHook;
import com.onurkat.reclazz.platform.BackofficeSocketEventDetector;
import java.util.Set;

/** Reports added handler binding boundaries without refreshing native widgets. */
public class BackofficeWidgetReloader implements StructuralReloadHook {
    @Override public void afterReload(Class<?> type) { reloadWidget(type); }

    @Override public void afterReload(Class<?> type, List<BackofficeSocketEventDetector.Handler> handlers) {
        reloadWidget(type, handlers);
    }

    /** No native widget refresh is claimed. */
    public static boolean reloadWidget(Class<?> widgetClass) {
        return reloadWidget(widgetClass, null);
    }

    public static boolean reloadWidget(Class<?> widgetClass, List<BackofficeSocketEventDetector.Handler> handlers) {
        BackofficeSocketEventAdvice.report(widgetClass, handlers);
        return false;
    }

    /** Compatibility entry point; detection belongs to the free platform layer. */
    public static boolean hasAddedSocketEvent(byte[] bytecode, Set<String> addedMethods) {
        return BackofficeSocketEventDetector.hasAddedSocketEvent(bytecode, addedMethods);
    }
}
