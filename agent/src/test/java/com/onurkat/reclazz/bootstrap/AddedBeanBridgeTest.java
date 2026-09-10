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

class AddedBeanBridgeTest {
    static class Owner {
        int sum(int a, int b) { return a + b; }
        int product(int a, int b) { return a * b; }
    }

    @Test
    void missingFactoryHasDistinctReferenceAndBodyCallSites() throws Throwable {
        String descriptor = "()Ljava/lang/Object;";
        String hash = InjectedNames.descHash(descriptor), key = InjectedNames.siteKey("addedFactory", hash);
        Object product = new Object(), reference = new Object();
        var target = MethodHandles.dropArguments(MethodHandles.constant(Object.class, product), 0, Owner.class);
        DispatchTable.getOrCreate(Owner.class).retarget(Map.of(key, target));
        AddedBeanBridge.publish(Owner.class, Map.of(key, (receiver, args, raw) -> reference));
        try {
            var type = MethodType.methodType(Object.class, Owner.class);
            var lookup = MethodHandles.lookup(); String name = Owner.class.getName().replace('.', '/');
            var bodySite = ReclazzBootstrap.bootstrapBody(lookup, "addedFactory", type, name, hash);
            var body = bodySite.dynamicInvoker();
            var frozen = bodySite.getTarget();
            var call = ReclazzBootstrap.bootstrapMethod(lookup, "addedFactory", type, name, hash).dynamicInvoker();
            Owner receiver = new Owner();
            assertSame(product, (Object) body.invokeExact(receiver));
            assertSame(reference, (Object) call.invokeExact(receiver));
            Object next = new Object();
            DispatchTable.getOrCreate(Owner.class).retarget(Map.of(key,
                    MethodHandles.dropArguments(MethodHandles.constant(Object.class, next), 0, Owner.class)));
            assertSame(next, (Object) body.invokeExact(receiver));
            assertSame(product, (Object) frozen.invokeExact(receiver), "lifecycle captures must remain frozen");
        } finally { AddedBeanBridge.publish(Owner.class, Map.of()); }
    }

    @Test
    void retainedSiteObservesFirstPublicationRemovalRestorationAndRawRetargeting() throws Throwable {
        var lookup = MethodHandles.lookup();
        var type = MethodType.methodType(int.class, int.class, int.class);
        var direct = new MutableCallSite(lookup.findVirtual(Owner.class, "sum", type));
        var owner = new Owner();
        var calls = new AtomicInteger();
        AddedBeanBridge.Invocation operation = (receiver, args, body) -> {
            assertSame(owner, receiver);
            assertArrayEquals(new Object[]{2, 3}, args);
            calls.incrementAndGet();
            return 2 * (int) body.invokeExact((Owner) receiver, (int) args[0], (int) args[1]);
        };
        AddedBeanBridge.publish(Owner.class, Map.of());
        try {
            var held = AddedBeanBridge.call(Owner.class, "operation", direct).dynamicInvoker();
            assertEquals(5, (int) held.invokeExact(owner, 2, 3));
            AddedBeanBridge.publish(Owner.class, Map.of("operation", operation));
            assertEquals(10, (int) held.invokeExact(owner, 2, 3));
            direct.setTarget(lookup.findVirtual(Owner.class, "product", type));
            MutableCallSite.syncAll(new MutableCallSite[]{direct});
            assertEquals(12, (int) held.invokeExact(owner, 2, 3));
            AddedBeanBridge.publish(Owner.class, Map.of());
            assertEquals(6, (int) held.invokeExact(owner, 2, 3));
            direct.setTarget(lookup.findVirtual(Owner.class, "sum", type));
            MutableCallSite.syncAll(new MutableCallSite[]{direct});
            assertEquals(5, (int) held.invokeExact(owner, 2, 3));
            AddedBeanBridge.publish(Owner.class, Map.of("operation", operation));
            assertEquals(10, (int) held.invokeExact(owner, 2, 3));
            assertEquals(3, calls.get());
        } finally {
            AddedBeanBridge.publish(Owner.class, Map.of());
        }
    }
}
