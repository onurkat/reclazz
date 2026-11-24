/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Identity of a broker factory's actual annotated endpoint, container and final adapter. */
public final class NativeListenerSetup {
    private NativeListenerSetup() { }
    public interface BeforeStart {
        void verify(Object endpoint, Object container, Object adapter) throws Exception;
    }

    private record Pending(Object processor, Object bean, BeforeStart check, Pending previous) { }
    private static final ThreadLocal<Pending> pending = new ThreadLocal<>();

    /** Nested registrations restore the previous verification when Spring returns. */
    public static AutoCloseable beforeStart(Object processor, Object bean, BeforeStart check) {
        Pending parent = pending.get();
        pending.set(new Pending(processor, bean, check, parent));
        return () -> {
            if (parent == null) pending.remove();
            else pending.set(parent);
        };
    }

    private static final ReferenceQueue<Object> collected = new ReferenceQueue<>();

    private static final class Key extends WeakReference<Object> {
        private final int hash;

        Key(Object object) {
            super(object, collected);
            hash = System.identityHashCode(object);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object value = get();
            return value != null && other instanceof Key key && value == key.get();
        }
    }

    private record Origin(WeakReference<Object> processor, WeakReference<Object> bean) {
        boolean matches(Object expectedProcessor, Object expectedBean) {
            return expectedBean != null && bean.get() == expectedBean && processor.get() == expectedProcessor;
        }
    }

    private record Receipt(Origin origin, WeakReference<Object> factory, WeakReference<Object> adapter) { }

    private static final Map<Key, Boolean> observed = new HashMap<>();
    private static final Map<Key, Origin> endpoints = new HashMap<>();
    private static final Map<Key, Receipt> containers = new HashMap<>();

    private static void drain() {
        Object key;
        while ((key = collected.poll()) != null) {
            observed.remove(key);
            endpoints.remove(key);
            containers.remove(key);
        }
    }

    /** Both native base constructors must have run with their measured hooks installed. */
    public static synchronized void constructed(Object instance) {
        drain();
        observed.put(new Key(instance), Boolean.TRUE);
    }

    public static synchronized boolean observed(Object processor, Object factory) {
        drain();
        return observed.containsKey(new Key(processor)) && observed.containsKey(new Key(factory));
    }

    public static synchronized void annotated(Object endpoint, Object bean, Object processor) {
        drain();
        endpoints.put(new Key(endpoint), new Origin(new WeakReference<>(processor), new WeakReference<>(bean)));
    }

    public static void created(Object container, Object endpoint, Object factory) {
        Origin origin;
        synchronized (NativeListenerSetup.class) {
            drain();
            origin = endpoints.get(new Key(endpoint));
        }
        if (origin == null || container == null) return;

        Pending guard = pending.get();
        while (guard != null && !origin.matches(guard.processor, guard.bean)) guard = guard.previous;
        Object adapter;
        try {
            Object holder = switch (container.getClass().getName()) {
                case "org.springframework.kafka.listener.ConcurrentMessageListenerContainer" ->
                        container.getClass().getMethod("getContainerProperties").invoke(container);
                case "org.springframework.jms.listener.DefaultMessageListenerContainer",
                     "org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer" -> container;
                default -> null;
            };
            if (holder == null) {
                if (guard != null) throw new IllegalStateException("unrecognized native listener container");
                return;
            }
            adapter = holder.getClass().getMethod("getMessageListener").invoke(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (guard != null) throw new IllegalStateException("native listener setup unavailable", failure);
            return;
        }
        synchronized (NativeListenerSetup.class) {
            containers.put(new Key(container), new Receipt(origin, new WeakReference<>(factory), new WeakReference<>(adapter)));
        }
        if (guard != null) {
            try {
                guard.check.verify(endpoint, container, adapter);
            } catch (Exception failure) {
                throw new IllegalStateException("native listener setup refused before consumer startup", failure);
            }
        }
    }

    private static Receipt receipt(Object container) {
        drain();
        return containers.get(new Key(container));
    }

    public static synchronized Object factory(Object container) {
        Receipt receipt = receipt(container);
        return receipt == null ? null : receipt.factory.get();
    }

    public static synchronized Object originBean(Object container, Object processor) {
        Receipt receipt = receipt(container);
        return receipt != null && receipt.origin.processor.get() == processor ? receipt.origin.bean.get() : null;
    }

    public static synchronized boolean originalAdapter(Object container, Object adapter, Object processor, Object bean) {
        Receipt receipt = receipt(container);
        return receipt != null && receipt.origin.matches(processor, bean) && adapter != null && receipt.adapter.get() == adapter;
    }

    public static synchronized boolean installed(Object container, Object adapter, Object processor, Object bean, Object factory) {
        Receipt receipt = receipt(container);
        return receipt != null && receipt.origin.matches(processor, bean) && adapter != null && receipt.adapter.get() == adapter
                && factory != null && receipt.factory.get() == factory;
    }

    public static synchronized boolean produced(Object container, Object processor, Object bean) {
        Receipt receipt = receipt(container);
        return receipt != null && receipt.origin.matches(processor, bean);
    }
}
