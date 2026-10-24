/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ext.StructuralReloadHook;
import com.onurkat.reclazz.platform.BackofficeSocketEventDetector;
import java.util.Set;

/** Reports widget registrations that class redefinition does not install. */
public class BackofficeWidgetReloader implements StructuralReloadHook {
    @Override public void afterReload(Class<?> type) { reloadWidget(type); }

    /** Called only for an added socket action; no native widget refresh is claimed. */
    public static boolean reloadWidget(Class<?> widgetClass) {
        String reason = "added Backoffice @SocketEvent actions are not registered in existing widgets; restart required";
        StatusReporter.warn(widgetClass.getName() + ": " + reason);
        RestartLedger.note(widgetClass.getName(), reason);
        return false;
    }

    /** Compatibility entry point; detection belongs to the free platform layer. */
    public static boolean hasAddedSocketEvent(byte[] bytecode, Set<String> addedMethods) {
        return BackofficeSocketEventDetector.hasAddedSocketEvent(bytecode, addedMethods);
    }
}
