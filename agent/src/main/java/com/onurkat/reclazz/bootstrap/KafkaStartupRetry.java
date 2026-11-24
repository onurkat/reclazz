/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Observes the native registration call and the exact products of that call. */
public final class KafkaStartupRetry {
    public interface Observer {
        boolean replay(Object[] arguments) throws Throwable;
        Object before(Object[] arguments) throws ReflectiveOperationException;
        void completed(Object[] arguments, Object prior, List<Object> endpoints, Map<String,Object> products)
                throws ReflectiveOperationException;
    }
    private static volatile Observer observer;
    private static final class Frame {
        final List<Object> endpoints = new ArrayList<>();
        final Map<String,Object> products = new LinkedHashMap<>();
    }
    private static final ThreadLocal<Frame> current = new ThreadLocal<>();
    private static final class ContainerIdentity implements Supplier<Object> {
        volatile WeakReference<Object> container = new WeakReference<>(null);
        @Override public Object get() { return container.get(); }
    }
    private static final Map<Object,ContainerIdentity> nativeContainers = new WeakHashMap<>();

    private KafkaStartupRetry() { }
    public static void observe(Observer value) { observer = value; }

    public static void configure(Object configurer, Object callback, Object endpoint, Object configuration,
            Object registrar, Object factory, String defaultFactory, MethodHandle original) throws Throwable {
        Object[] args = {configurer, callback, endpoint, configuration, registrar, factory, defaultFactory};
        Observer active = observer;
        if (active != null && active.replay(args)) return;
        Object prior = null;
        if (active != null) {
            try { prior = active.before(args); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* Native startup proceeds. */ }
        }
        Frame parent = current.get();
        var frame = prior == null ? null : new Frame();
        current.set(frame);
        try {
            original.invokeWithArguments(args);
            if (frame != null) {
                try { active.completed(args, prior, frame.endpoints, frame.products); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* No receipt. */ }
            }
        } finally {
            if (parent == null) current.remove();
            else current.set(parent);
        }
    }

    public static void endpoint(Object endpoint) {
        Frame frame = current.get();
        if (frame != null) {
            frame.endpoints.add(endpoint);
            synchronized (nativeContainers) { nativeContainers.put(endpoint, new ContainerIdentity()); }
        }
    }

    public static void product(String name, Object bean) {
        Frame frame = current.get();
        if (frame != null) frame.products.put(name, bean);
    }

    public static void constructed(Object container, Object endpoint) {
        synchronized (nativeContainers) {
            var identity = nativeContainers.get(endpoint);
            if (identity != null) identity.container = new WeakReference<>(container);
        }
    }
    public static Supplier<Object> identityFor(Object endpoint) {
        synchronized (nativeContainers) { return nativeContainers.get(endpoint); }
    }
}
