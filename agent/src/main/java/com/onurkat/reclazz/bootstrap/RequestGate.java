/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

/** Admission for synchronous MVC dispatches and the single reload thread. */
public final class RequestGate {
    private static final RequestGate GLOBAL = new RequestGate();
    private final ThreadLocal<Integer> depth = new ThreadLocal<>();
    private int active;
    private boolean closed;
    private boolean installed;
    private String failure;
    private Thread writer;

    public static RequestGate global() { return GLOBAL; }

    public synchronized void installed() {
        installed = true;
        notifyAll();
    }

    /** A failed hook must never silently turn the requested protection off. */
    public synchronized void unavailable(String reason) {
        failure = reason;
        notifyAll();
    }

    public void enter() {
        Integer nested = depth.get();
        if (nested != null) {
            depth.set(nested + 1);
            return;
        }
        boolean interrupted = false;
        synchronized (this) {
            // A forward/include from an existing request must not wait for the
            // writer that is waiting for that very request to return.
            while (closed && writer != Thread.currentThread()) {
                try { wait(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            active++;
            depth.set(1);
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void exit() {
        Integer nested = depth.get();
        if (nested == null) throw new IllegalStateException("Unbalanced request boundary");
        if (nested > 1) {
            depth.set(nested - 1);
            return;
        }
        depth.remove();
        synchronized (this) {
            active--;
            notifyAll();
        }
    }

    /** Close admission for at most timeoutMillis while existing requests drain. */
    public synchronized boolean tryBeginReload(long timeoutMillis) throws InterruptedException {
        if (depth.get() != null || closed) throw new IllegalStateException("Reload boundary already held");
        if (!installed || failure != null) return false;
        long remaining = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long start = System.nanoTime();
        closed = true;
        try {
            while (active != 0) {
                if (failure != null) return false;
                if (remaining <= 0) return false;
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                        - (System.nanoTime() - start);
            }
            if (failure != null) return false;
            writer = Thread.currentThread();
            return true;
        } finally {
            if (writer == null) {
                closed = false;
                notifyAll();
            }
        }
    }

    /** After a timeout, let traffic through until a natural idle boundary exists. */
    public synchronized void awaitIdle() throws InterruptedException {
        while (active != 0 || !installed || failure != null) wait();
    }

    public synchronized void endReload() {
        if (writer != Thread.currentThread()) throw new IllegalStateException("Not the reload owner");
        writer = null;
        closed = false;
        notifyAll();
    }

    public synchronized String waitingFor() {
        if (failure != null) return failure;
        if (!installed) return "Spring MVC request hook has not loaded";
        return active + " active synchronous MVC requests";
    }
}
