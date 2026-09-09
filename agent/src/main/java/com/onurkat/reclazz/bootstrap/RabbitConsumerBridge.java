/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/** Counts complete Rabbit worker lifetimes, including queued tasks and channel cleanup. */
public final class RabbitConsumerBridge {
    private RabbitConsumerBridge() { }
    private static final class State { int workers; boolean retiring; }
    // Values never reference their weak container keys.
    private static final Map<Object, State> containers = new WeakHashMap<>();
    private static final ClassValue<boolean[]> hooked = new ClassValue<>() {
        @Override protected boolean[] computeValue(Class<?> type) { return new boolean[1]; }
    };
    public static void markHooked(Class<?> type) { hooked.get(type)[0] = true; }
    public static boolean isHooked(Class<?> type) { return hooked.get(type)[0]; }
    public static void created(Object container) {
        synchronized (containers) {
            State state = containers.computeIfAbsent(container, key -> new State());
            if (state.retiring) throw new IllegalStateException("Rabbit container is retiring; new worker refused");
            state.workers++;
        }
    }
    public static void finished(Object container) {
        synchronized (containers) {
            State state = containers.get(container);
            if (state == null || state.workers <= 0) throw new IllegalStateException("Untracked Rabbit worker exit");
            state.workers--;
            containers.notifyAll();
        }
    }
    public static void retire(Object container) {
        synchronized (containers) { containers.computeIfAbsent(container, key -> new State()).retiring = true; }
    }
    public static boolean awaitStopped(Object container, long deadlineNanos) throws InterruptedException {
        synchronized (containers) {
            State state = containers.get(container);
            if (state == null || !state.retiring) throw new IllegalStateException("Rabbit retirement was not started");
            while (state.workers != 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(containers, remaining);
            }
            return true;
        }
    }
}
