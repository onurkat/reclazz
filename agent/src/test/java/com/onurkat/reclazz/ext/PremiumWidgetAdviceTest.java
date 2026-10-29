/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.hybris.backoffice.BackofficeWidgetReloader;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PremiumWidgetAdviceTest {
    @Test void shippedFactoryUsesTheFreeDefaultHook() {
        var platform = (com.onurkat.reclazz.platform.PlatformContext) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.onurkat.reclazz.platform.PlatformContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPlatformId")) return com.onurkat.reclazz.platform.PlatformContext.Platform.GENERIC;
                    throw new UnsupportedOperationException(method.getName());
                });
        var module = new com.onurkat.reclazz.hybris.SapPremiumModuleFactory().create(new PremiumContext() {
            public com.onurkat.reclazz.platform.PlatformContext platform() { return platform; }
            public com.onurkat.reclazz.config.AgentConfig config() { return com.onurkat.reclazz.config.AgentConfig.parse(null); }
            public com.onurkat.reclazz.platform.PropertyFileSnapshots propertySnapshots() { return new com.onurkat.reclazz.platform.PropertyFileSnapshots(); }
            public java.lang.instrument.Instrumentation instrumentation() { return null; }
            public int applyLoggerLevels(Map<String, String> content, Collection<String> keys) { throw new AssertionError(); }
            public boolean requestRunningSystemUpdate() { throw new AssertionError(); }
        });
        assertInstanceOf(NoopStructuralReloadHook.class, module.structuralReloadHook());
    }

    @Test void freeHookReportsExactlyTheExistingPremiumWidgetAdvice() {
        List<String> actual = new ArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> actual.add(level + ":" + message);
        StatusReporter.addListener(listener);
        RestartLedger.clear();
        try {
            assertFalse(BackofficeWidgetReloader.reloadWidget(getClass()));
            var oldMessages = List.copyOf(actual);
            var oldLedger = RestartLedger.digest();
            assertFalse(oldMessages.isEmpty()); assertFalse(oldLedger.isEmpty());
            actual.clear(); RestartLedger.clear();
            new NoopStructuralReloadHook().afterReload(getClass());
            assertEquals(oldMessages, actual);
            assertEquals(oldLedger, RestartLedger.digest());
        } finally { StatusReporter.removeListener(listener); RestartLedger.clear(); }
    }
}
