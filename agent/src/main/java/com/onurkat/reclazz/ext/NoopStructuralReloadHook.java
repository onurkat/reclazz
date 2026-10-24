/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;

/** No platform mutation; keep the existing unsupported-registration finding. */
public final class NoopStructuralReloadHook implements StructuralReloadHook {
    @Override public void afterReload(Class<?> type) {
        String reason = "added Backoffice @SocketEvent actions are not registered in existing widgets; restart required";
        StatusReporter.warn(type.getName() + ": " + reason);
        RestartLedger.note(type.getName(), reason);
    }
}
