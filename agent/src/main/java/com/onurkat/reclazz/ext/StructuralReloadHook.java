/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

/** Invoked at the existing added-widget-registration point after redefinition. */
@FunctionalInterface
public interface StructuralReloadHook {
    void afterReload(Class<?> type);

    /** Existing custom hooks retain their single-class callback. */
    default void afterReload(Class<?> type,
            java.util.List<com.onurkat.reclazz.platform.BackofficeSocketEventDetector.Handler> handlers) {
        afterReload(type);
    }
}
