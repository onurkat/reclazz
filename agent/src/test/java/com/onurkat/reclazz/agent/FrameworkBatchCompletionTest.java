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

    @Test void failedDeferredNativeReloadLeavesBaselineAndFrameworkUntouched() throws Exception {
        byte[] before = getClass().getResourceAsStream("FrameworkBatchCompletionTest.class").readAllBytes();
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(before).accept(node, 0);
        node.visitAnnotation("Lexample/Changed;", true).visitEnd();
        var writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer);
        byte[] changed = writer.toByteArray();
        var type = getClass();
        com.onurkat.reclazz.transform.KeepBaseline.capture(type.getClassLoader(), type.getName().replace('.', '/'), before);
        var baseline = com.onurkat.reclazz.transform.KeepBaseline.get(type);
        assertNotEquals(baseline, com.onurkat.reclazz.transform.KeepBaseline.fingerprint(changed));
        var structural = new com.onurkat.reclazz.reload.StructuralReloader(null, null, null, null);
        var outcomes = structural.getClass().getDeclaredField("nativeOutcomes"); outcomes.setAccessible(true);
        @SuppressWarnings("unchecked") var map = (java.util.Map<Class<?>, com.onurkat.reclazz.reload.StructuralReloader.NativeOutcome>) outcomes.get(structural);
        map.put(type, com.onurkat.reclazz.reload.StructuralReloader.NativeOutcome.FAILED);
        var field = ReclazzAgent.class.getDeclaredField("structuralReloader"); field.setAccessible(true);
        Object previous = field.get(null);
        var workType = Class.forName(ReclazzAgent.class.getName() + "$FrameworkReload");
        var constructor = workType.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        // A null Spring collaborator proves that the failed terminal outcome returns before framework work.
        Object work = constructor.newInstance(type.getName(), type, ClassReloader.ReloadResult.success(true, false),
                changed, false, "FrameworkBatchCompletionTest.java", 0L, false, null, null, null);
        var complete = ReclazzAgent.class.getDeclaredMethod("completeFramework", workType, boolean.class);
        complete.setAccessible(true);
        try {
            field.set(null, structural); complete.invoke(null, work, true);
            assertSame(baseline, com.onurkat.reclazz.transform.KeepBaseline.get(type));
            var completed = workType.getDeclaredField("frameworkCompleted"); completed.setAccessible(true);
            assertFalse(completed.getBoolean(work));
        } finally { field.set(null, previous); com.onurkat.reclazz.ui.ReloadEffects.end(); }
    }

    private void exercise(boolean failingReceipt) throws Exception {
        var order = new ArrayList<String>();
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p, m, args) ->
                        m.getName().equals("getAllApplicationContexts") ? List.of() : null);
        var spring = new SpringReloadOrchestrator(platform) {
            @Override public void onClassReloaded(String name, Class<?> type, boolean structural,
                    boolean annotations, boolean added, java.util.Set<String> signatures, byte[] bytes,
                    com.onurkat.reclazz.transform.KeepBaseline.Input baseline) {
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
        Object work = constructor.newInstance(name, getClass(), ClassReloader.ReloadResult.success(true, false),
                getClass().getResourceAsStream("FrameworkBatchCompletionTest.class").readAllBytes(), false, "FrameworkBatchCompletionTest.java", 0L, false, spring, null, null);
        var begin = ReclazzAgent.class.getDeclaredMethod("beginReloadBatch", SpringReloadOrchestrator.class);
        var defer = ReclazzAgent.class.getDeclaredMethod("deferOrComplete", workType);
        var end = ReclazzAgent.class.getDeclaredMethod("endReloadBatch", SpringReloadOrchestrator.class);
        begin.setAccessible(true); defer.setAccessible(true); end.setAccessible(true);
        StatusReporter.addListener(listener);
        try {
            begin.invoke(null, spring);
            defer.invoke(null, work);
            if (failingReceipt) defer.invoke(null, constructor.newInstance("java.lang.String", String.class,
                    ClassReloader.ReloadResult.success(true, false), new byte[0], false,
                    "String.java", 0L, false, spring, null, null));
            end.invoke(null, spring);
            assertEquals(failingReceipt
                    ? List.of("framework", "framework", "deferred bean recreation", "RELOAD receipt", "sibling receipt")
                    : List.of("framework", "deferred bean recreation", "RELOAD receipt"), order);
        } finally { StatusReporter.removeListener(listener); }
    }
}
