/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.platform.BackofficeSocketEventAdvice;
import com.onurkat.reclazz.platform.BackofficeSocketEventDetector.Handler;
import java.util.List;

/** Advice only; never mutates native widget state. */
public final class NoopStructuralReloadHook implements StructuralReloadHook {
    @Override public void afterReload(Class<?> type) { BackofficeSocketEventAdvice.report(type, null); }
    @Override public void afterReload(Class<?> type, List<Handler> handlers) {
        BackofficeSocketEventAdvice.report(type, handlers);
    }
}
