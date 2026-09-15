/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Once a reload has installed a companion target for a key, no call site
 * created for that key may ever be handed out with its initial target
 * still in place, however many threads bootstrap it at once.
 */
class DispatchSitePublicationTest {

    static String good() {
        return "good";
    }

    static String initial() {
        throw new UnsupportedOperationException("initial target ran");
    }

    @Test
    void aSiteCreatedAfterARetargetNeverRunsItsInitialTarget() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType type = MethodType.methodType(String.class);
        MethodHandle good = lookup.findStatic(getClass(), "good", type);
        MethodHandle initial = lookup.findStatic(getClass(), "initial", type);
        DispatchTable.ClassDispatch dispatch = DispatchTable.getOrCreate(getClass());

        int rounds = 400;
        int threads = 8;
        AtomicInteger failures = new AtomicInteger();
        for (int round = 0; round < rounds; round++) {
            String key = "k" + round;
            dispatch.retarget(Map.of(key, good));              // the callee reloaded first
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                Thread thread = new Thread(() -> {
                    try {
                        go.await();
                        MutableCallSite site = dispatch.getOrCreateMethodSite(key, new MutableCallSite(initial));
                        try {
                            site.getTarget().invoke();
                        } catch (Throwable ranTheInitialTarget) {
                            failures.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        // counted as nothing
                    } finally {
                        done.countDown();
                    }
                });
                thread.setDaemon(true);
                thread.start();
            }
            go.countDown();
            done.await();
        }
        assertEquals(0, failures.get(), "sites handed out with the initial target still in place");
    }

    @Test
    void aSiteCreatedBeforeTheRetargetIsRetargeted() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType type = MethodType.methodType(String.class);
        MethodHandle good = lookup.findStatic(getClass(), "good", type);
        MethodHandle initial = lookup.findStatic(getClass(), "initial", type);
        DispatchTable.ClassDispatch dispatch = DispatchTable.getOrCreate(List.class);

        MutableCallSite site = dispatch.getOrCreateMethodSite("before", new MutableCallSite(initial));
        assertThrows(UnsupportedOperationException.class, () -> site.getTarget().invoke(),
                "before any reload the initial target is what there is");
        dispatch.retarget(Map.of("before", good));
        assertEquals("good", (String) site.getTarget().invoke());
    }
    public static class Base { public String value() { return "original"; } }
    public static class ProxyReceiver extends Base { @Override public String value() { return "intercepted"; } }

    @Test
    void aColdSiteAfterReloadStillUsesTheReceiversOverride() throws Throwable {
        var dispatch = DispatchTable.getOrCreate(Base.class);
        var body = MethodHandles.dropArguments(MethodHandles.constant(String.class, "updated"), 0, Base.class);
        dispatch.retarget(Map.of("coldProxy", body));
        var publicCall = MethodHandles.lookup().findVirtual(Base.class, "value", MethodType.methodType(String.class));
        dispatch.registerOverrideGuard("coldProxy", Base.class, "value", publicCall);
        var site = dispatch.getOrCreateMethodSite("coldProxy", new MutableCallSite(publicCall));
        assertEquals("intercepted", (String) site.dynamicInvoker().invokeExact((Base) new ProxyReceiver()));
        assertEquals("updated", (String) site.dynamicInvoker().invokeExact(new Base()));
    }

}
