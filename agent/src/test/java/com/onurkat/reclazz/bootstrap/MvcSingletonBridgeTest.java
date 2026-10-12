/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MvcSingletonBridgeTest {
    @BeforeEach @AfterEach void clear() { MvcSingletonBridge.clearForTests(); }
    static class Equal {
        @Override public boolean equals(Object other) { return other instanceof Equal; }
        @Override public int hashCode() { return 1; }
    }
    private static void publish(Object old, Object fresh) {
        var pairs = new IdentityHashMap<Object, Object>(); pairs.put(old, fresh); MvcSingletonBridge.publish(pairs);
    }
    @Test void equalityDoesNotCrossContextIdentitiesAndNativeListsAreUnchanged() {
        var first = new Equal(); var second = new Equal(); var fresh = new Equal();
        publish(first, fresh);
        assertSame(fresh, MvcSingletonBridge.current(first)); assertSame(second, MvcSingletonBridge.current(second));
        var original = List.of(second, first, second); var changed = MvcSingletonBridge.mappings(original);
        assertSame(second, changed.get(0)); assertSame(fresh, changed.get(1)); assertSame(second, changed.get(2));
        assertSame(first, original.get(1)); assertNotSame(original, changed);
        var unrelated = List.of(second); assertSame(unrelated, MvcSingletonBridge.mappings(unrelated));
        assertNull(MvcSingletonBridge.current(null)); assertNull(MvcSingletonBridge.mappings(null));
    }
    @Test void heldOriginalFollowsRepeatedAndBatchedGenerations() {
        Object first = new Object(), second = new Object(), third = new Object(), fourth = new Object();
        publish(first, second); publish(second, third); assertSame(third, MvcSingletonBridge.current(first));
        var pairs = new IdentityHashMap<Object, Object>(); pairs.put(third, fourth); pairs.put(second, third);
        MvcSingletonBridge.publish(pairs);
        assertSame(fourth, MvcSingletonBridge.current(first)); assertSame(fourth, MvcSingletonBridge.current(second));
        assertSame(fourth, MvcSingletonBridge.current(third));
    }
    @Test void cyclesAndChangedClassesAreRefused() {
        Object first = new Object(), second = new Object();
        var pairs = new IdentityHashMap<Object, Object>(); pairs.put(first, second); pairs.put(second, first);
        MvcSingletonBridge.publish(pairs); assertSame(first, MvcSingletonBridge.current(first));
        publish(first, "different class"); assertSame(first, MvcSingletonBridge.current(first));
    }
    private static List<WeakReference<Object>> weakPair() {
        Object old = new Object(), fresh = new Object(); publish(old, fresh);
        return List.of(new WeakReference<>(old), new WeakReference<>(fresh));
    }
    @Test void neitherOldNorCurrentInstanceIsKeptAlive() throws Exception {
        var refs = weakPair(); long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (refs.stream().anyMatch(ref -> ref.get() != null) && System.nanoTime() < until) { System.gc(); Thread.sleep(20); }
        for (var ref : refs) assertNull(ref.get(), "registry must not retain either side");
    }
    @Test void hookReceiptsAreSeparatedByLoaderAndRequireBothBitsWhenAsked() {
        ClassLoader first = new ClassLoader() { }, second = new ClassLoader() { };
        MvcSingletonBridge.installed(first, 1);
        assertTrue(MvcSingletonBridge.available(first, 1)); assertFalse(MvcSingletonBridge.available(first, 3));
        assertFalse(MvcSingletonBridge.available(second, 1)); MvcSingletonBridge.installed(first, 2);
        assertTrue(MvcSingletonBridge.available(first, 3));
    }
}
