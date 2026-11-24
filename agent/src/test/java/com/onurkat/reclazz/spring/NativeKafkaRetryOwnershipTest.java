/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.KafkaRetryRoutes;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeKafkaRetryOwnershipTest {
    public static class Factory {
        final Map<String,Object> singletons = new HashMap<>();
        public Object getSingletonMutex() { return new Object(); }
        public Object getSingleton(String name) { return singletons.get(name); }
        public void registerSingleton(String name, Object bean) { singletons.put(name, bean); }
        public void destroySingleton(String name) { singletons.remove(name); }
    }

    public static class Registry {
        boolean contextRefreshed = true;
        final Map<String,Object> containers = new HashMap<>();
        public Registry getEndpointRegistry() { return this; }
        public Object getListenerContainer(String name) { return containers.get(name); }
    }

    public static class Resolver {
        final Map<Object,Object> sourceDestinationsHolderMap = new HashMap<>();
        int probes;
        boolean instrumented = true;
        public Object getDestinationTopicByName(String topic) {
            probes++;
            return instrumented ? KafkaRetryRoutes.view(this, sourceDestinationsHolderMap).get(topic)
                    : sourceDestinationsHolderMap.get(topic);
        }
    }

    public void listener(String payload) { }

    private static void observed(Factory factory, Registry registry, Resolver resolver, String id) throws Exception {
        Method method = NativeKafkaRetryOwnershipTest.class.getMethod("listener", String.class);
        Object holder = new Object();
        Object container = new Object();
        registry.containers.put(id, container);
        resolver.sourceDestinationsHolderMap.put(id, holder);
        NativeKafkaRetryOwnership.recordStartup(factory, resolver, registry, method, id, "2.9.13",
                Map.of(id, holder), Map.of(), Map.of(id, () -> container));
    }

    @Test void factoryLockIsStableAcrossThreadsAndFactoriesAreIndependent() throws Exception {
        Factory factory = new Factory();
        Object lock = SpringKafkaRetryRegistration.ownershipMutex(factory);
        assertSame(lock, java.util.concurrent.CompletableFuture.supplyAsync(
                () -> SpringKafkaRetryRegistration.ownershipMutex(factory)).get());
        assertNotSame(lock, SpringKafkaRetryRegistration.ownershipMutex(new Factory()));
    }

    @Test void aFailedStartupProbeIsDroppedWhileTheHealthyReceiptIsAdopted() throws Exception {
        var factory = new Factory();
        var registry = new Registry();
        var broken = new Resolver();
        broken.instrumented = false;
        var healthy = new Resolver();
        observed(factory, registry, broken, "unobserved");
        observed(factory, registry, healthy, "replayable");
        assertTrue(factory.singletons.isEmpty(), "startup observation added an application singleton");
        var groups = SpringKafkaRetryRegistration.groups(factory);
        assertEquals(1, groups.size());
        assertEquals("replayable", groups.get(0).mainId);
        groups.get(0).verifyOwnership();
        assertEquals(groups, SpringKafkaRetryRegistration.groups(factory));
        assertEquals(1, broken.probes, "discarded receipt must not run on the next reload");
    }

    @Test void twoStartupIdsSharingOneMethodRefuseBeforeEitherContainerIsStopped() throws Exception {
        var factory = new Factory();
        var registry = new Registry();
        var resolver = new Resolver();
        observed(factory, registry, resolver, "first");
        observed(factory, registry, resolver, "second");
        var groups = SpringKafkaRetryRegistration.groups(factory);
        assertEquals(2, groups.size());
        for (var group : groups) {
            var error = assertThrows(IllegalStateException.class, group::verifyOwnership);
            assertTrue(error.getMessage().contains("share the same callback"));
            assertSame(registry.containers.get(group.mainId), group.containers().get(0).instance());
        }
    }
}
