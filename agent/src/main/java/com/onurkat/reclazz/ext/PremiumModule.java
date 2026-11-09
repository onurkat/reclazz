/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Supplied explicitly by the composition root; no application-classpath scanning. */
public interface PremiumModule {
    default int contractVersion() { return PremiumModules.CONTRACT_VERSION; }
    Set<Capability> capabilities();
    default Map<Capability, PlatformChangeHandler> changeHandlers() { return Map.of(); }
    default StructuralReloadHook structuralReloadHook() { return new NoopStructuralReloadHook(); }
    default com.onurkat.reclazz.platform.XmlBeanLifecycle xmlBeanLifecycle() {
        return com.onurkat.reclazz.platform.XmlBeanLifecycle.NONE;
    }
    default com.onurkat.reclazz.platform.PreservedSingletons preservedSingletons() {
        return com.onurkat.reclazz.platform.PreservedSingletons.NONE;
    }
    /** True means queued, never that stored search documents have been refreshed. */
    default boolean reindex(Class<?> type) { return false; }

    /** Preparation precedes the Spring cascade; completion follows it, under one admission. */
    default PreparedRefresh prepareInterceptor(String className, List<Object> contexts) { return null; }

    @FunctionalInterface
    interface PreparedRefresh {
        boolean complete();
    }
}
