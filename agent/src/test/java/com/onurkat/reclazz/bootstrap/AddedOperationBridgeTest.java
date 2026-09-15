/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AddedOperationBridgeTest {
    static class Owner {
        int sum(int a, int b) { return a + b; }
        int product(int a, int b) { return a * b; }
    }

    @Test
    void retainedSiteObservesFirstPublicationRemovalRestorationAndRawRetargeting() throws Throwable {
        var lookup = MethodHandles.lookup();
        var type = MethodType.methodType(int.class, int.class, int.class);
        var direct = new MutableCallSite(lookup.findVirtual(Owner.class, "sum", type));
        var owner = new Owner();
        var calls = new AtomicInteger();
        AddedOperationBridge.Invocation operation = (receiver, args, body) -> {
            assertSame(owner, receiver);
            assertArrayEquals(new Object[]{2, 3}, args);
            calls.incrementAndGet();
            return 2 * (int) body.invokeExact((Owner) receiver, (int) args[0], (int) args[1]);
        };
        AddedOperationBridge.publish(Owner.class, Map.of());
        try {
            var held = AddedOperationBridge.externalCall(Owner.class, "operation", direct).dynamicInvoker();
            assertEquals(5, (int) held.invokeExact(owner, 2, 3));
            AddedOperationBridge.publish(Owner.class, Map.of("operation", operation));
            assertEquals(10, (int) held.invokeExact(owner, 2, 3));
            direct.setTarget(lookup.findVirtual(Owner.class, "product", type));
            MutableCallSite.syncAll(new MutableCallSite[]{direct});
            assertEquals(12, (int) held.invokeExact(owner, 2, 3));
            AddedOperationBridge.publish(Owner.class, Map.of());
            assertEquals(6, (int) held.invokeExact(owner, 2, 3));
            direct.setTarget(lookup.findVirtual(Owner.class, "sum", type));
            MutableCallSite.syncAll(new MutableCallSite[]{direct});
            assertEquals(5, (int) held.invokeExact(owner, 2, 3));
            AddedOperationBridge.publish(Owner.class, Map.of("operation", operation));
            assertEquals(10, (int) held.invokeExact(owner, 2, 3));
            assertEquals(3, calls.get());
        } finally {
            AddedOperationBridge.publish(Owner.class, Map.of());
        }
    }
    @Test
    void admittedInvocationKeepsItsBodyAfterTheRawSiteIsRetargeted() throws Throwable {
        var lookup = MethodHandles.lookup();
        var type = MethodType.methodType(int.class, int.class, int.class);
        var direct = new MutableCallSite(lookup.findVirtual(Owner.class, "sum", type));
        var saved = new java.util.concurrent.atomic.AtomicReference<java.lang.invoke.MethodHandle>();
        AddedOperationBridge.publish(Owner.class, Map.of("queued", (receiver, args, body) -> {
            saved.set(body); return 0;
        }));
        try {
            var owner = new Owner();
            var call = AddedOperationBridge.externalCall(Owner.class, "queued", direct).dynamicInvoker();
            assertEquals(0, (int) call.invokeExact(owner, 2, 3));
            var admitted = saved.get();
            direct.setTarget(lookup.findVirtual(Owner.class, "product", type));
            MutableCallSite.syncAll(new MutableCallSite[]{direct});
            assertEquals(5, (int) admitted.invokeExact(owner, 2, 3));
            assertEquals(0, (int) call.invokeExact(owner, 2, 3));
            assertEquals(6, (int) saved.get().invokeExact(owner, 2, 3));
        } finally { AddedOperationBridge.publish(Owner.class, Map.of()); }
    }

}
