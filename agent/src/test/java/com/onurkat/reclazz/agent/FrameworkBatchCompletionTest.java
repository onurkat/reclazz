/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.spring.SpringReloadOrchestrator;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkBatchCompletionTest {
    @Test void reloadReceiptFollowsDeferredBeanRecreation() throws Exception { exercise(false); }
    @Test void aFailingReceiptDoesNotSuppressTheSiblingReceipt() throws Exception { exercise(true); }

    private void exercise(boolean failingReceipt) throws Exception {
        var order = new ArrayList<String>();
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p, m, args) ->
                        m.getName().equals("getAllApplicationContexts") ? List.of() : null);
        var spring = new SpringReloadOrchestrator(platform) {
            @Override public void onClassReloaded(String name, Class<?> type, boolean structural,
                    boolean annotations, boolean added, java.util.Set<String> signatures, byte[] bytes) {
                order.add("framework");
            }
            @Override public void endBatch() {
                super.endBatch();
                order.add("deferred bean recreation");
            }
        };
        String name = getClass().getName();
        StatusReporter.StatusListener listener = (level, message) -> {
            if (message.contains("Reloaded " + name)) {
                order.add("RELOAD receipt");
                if (failingReceipt) throw new AssertionError("test receipt delivery failure");
            }
            if (message.contains("Reloaded java.lang.String")) order.add("sibling receipt");
        };
        var workType = Class.forName(ReclazzAgent.class.getName() + "$FrameworkReload");
        var constructor = workType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object work = constructor.newInstance(name, ClassReloader.ReloadResult.success(true, false),
                new byte[0], false, "FrameworkBatchCompletionTest.java", 0L, false, spring, null);
        var begin = ReclazzAgent.class.getDeclaredMethod("beginReloadBatch", SpringReloadOrchestrator.class);
        var defer = ReclazzAgent.class.getDeclaredMethod("deferOrComplete", workType);
        var end = ReclazzAgent.class.getDeclaredMethod("endReloadBatch", SpringReloadOrchestrator.class);
        begin.setAccessible(true); defer.setAccessible(true); end.setAccessible(true);
        StatusReporter.addListener(listener);
        try {
            begin.invoke(null, spring);
            defer.invoke(null, work);
            if (failingReceipt) defer.invoke(null, constructor.newInstance("java.lang.String",
                    ClassReloader.ReloadResult.success(true, false), new byte[0], false,
                    "String.java", 0L, false, spring, null));
            end.invoke(null, spring);
            assertEquals(failingReceipt
                    ? List.of("framework", "framework", "deferred bean recreation", "RELOAD receipt", "sibling receipt")
                    : List.of("framework", "deferred bean recreation", "RELOAD receipt"), order);
        } finally { StatusReporter.removeListener(listener); }
    }
}
