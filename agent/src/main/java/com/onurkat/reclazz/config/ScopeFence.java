/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Serializes scope publication against native codegen admission, without waiting on an executor. */
public final class ScopeFence {
    private int activeRuns;
    private boolean closed, awaitingTransition;
    private Runnable wake;
    private final Set<Runnable> deferred = new LinkedHashSet<>();

    /** Null defers this admission until reopen. The stable callback is coalesced. */
    public synchronized Permit tryEnterRun(Runnable retry) {
        if (closed || awaitingTransition) {
            deferred.add(java.util.Objects.requireNonNull(retry));
            return null;
        }
        activeRuns++;
        return new Permit();
    }

    /** False registers exactly one dormant transition; callers must not enqueue a busy retry. */
    public synchronized boolean tryCloseOrRegister(Runnable resume) {
        if (closed) throw new IllegalStateException("Scope transition already owns the fence");
        if (activeRuns == 0) {
            closed = true; awaitingTransition = false; wake = null;
            return true;
        }
        if (wake != null) throw new IllegalStateException("Scope transition already waiting");
        wake = java.util.Objects.requireNonNull(resume); awaitingTransition = true;
        return false;
    }

    /** Releases the transition before invoking any deferred admission (never under the fence lock). */
    public void reopen() {
        List<Runnable> ready;
        synchronized (this) {
            closed = false; awaitingTransition = false; wake = null;
            ready = List.copyOf(deferred); deferred.clear();
        }
        for (Runnable retry : ready) retry.run();
    }

    public final class Permit implements AutoCloseable {
        private boolean released;
        private Permit() { }
        @Override public void close() {
            Runnable resume = null;
            synchronized (ScopeFence.this) {
                if (released) return;
                released = true;
                if (--activeRuns == 0 && wake != null) { resume = wake; wake = null; }
            }
            if (resume != null) resume.run();
        }
    }
}
