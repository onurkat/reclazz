/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.invoke.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class LambdaFactoryMarkerTest {
    public interface Marker {}
    public interface ObjectValue { Object get(); }
    public interface StringValue extends ObjectValue {
        String get();
        default String decorated() { return "[" + get() + "]"; }
    }

    private static String value(String captured) { return captured; }
    private static MethodHandle implementation() throws ReflectiveOperationException {
        return MethodHandles.lookup().findStatic(LambdaFactoryMarkerTest.class, "value",
                MethodType.methodType(String.class, String.class));
    }

    @Test
    void serializableCastSucceedsButSerializationStillRefuses() throws Throwable {
        CallSite site = LambdaFactory.altMetafactory(MethodHandles.lookup(), "get",
                MethodType.methodType(Supplier.class, String.class),
                MethodType.methodType(Object.class), implementation(), MethodType.methodType(String.class),
                LambdaMetafactory.FLAG_SERIALIZABLE);
        Object lambda = site.getTarget().invoke("captured");
        assertInstanceOf(Serializable.class, lambda);
        assertEquals("captured", ((Supplier<?>) lambda).get());
        try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
            assertThrows(NotSerializableException.class, () -> out.writeObject(lambda));
        }
    }

    @Test
    void applicationMarkerIsVisibleEvenWhenSamComesFromBootstrapLoader() throws Throwable {
        CallSite site = LambdaFactory.altMetafactory(MethodHandles.lookup(), "get",
                MethodType.methodType(Supplier.class, String.class),
                MethodType.methodType(Object.class), implementation(), MethodType.methodType(String.class),
                LambdaMetafactory.FLAG_MARKERS, 1, Marker.class);
        Object first = site.getTarget().invoke("first");
        Object second = site.getTarget().invoke("second");
        assertInstanceOf(Marker.class, first);
        assertEquals("first", ((Supplier<?>) first).get());
        assertEquals("second", ((Supplier<?>) second).get());
        assertTrue(first.equals(first));
        assertFalse(first.equals(second));
        assertFalse(first.equals(null));
        assertEquals(System.identityHashCode(first), first.hashCode());
        assertNotNull(first.toString());
    }

    @Test
    void serializableMarkerAndBridgeFlagsPreserveDefaultAndBothReturnTypes() throws Throwable {
        CallSite site = LambdaFactory.altMetafactory(MethodHandles.lookup(), "get",
                MethodType.methodType(StringValue.class, String.class),
                MethodType.methodType(String.class), implementation(), MethodType.methodType(String.class),
                LambdaMetafactory.FLAG_SERIALIZABLE | LambdaMetafactory.FLAG_MARKERS
                        | LambdaMetafactory.FLAG_BRIDGES,
                1, Marker.class, 1, MethodType.methodType(Object.class));
        Object lambda = site.getTarget().invoke("bridge");
        assertInstanceOf(Marker.class, lambda);
        assertInstanceOf(Serializable.class, lambda);
        assertEquals("bridge", ((StringValue) lambda).get());
        assertEquals("bridge", ((ObjectValue) lambda).get());
        assertEquals("[bridge]", ((StringValue) lambda).decorated());
    }
}
