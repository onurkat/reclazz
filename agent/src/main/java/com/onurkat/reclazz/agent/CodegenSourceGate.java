/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;

/** One native generation may include several coalesced Ant runs. */
final class CodegenSourceGate {
    private int active;
    private Runnable deferred;
    private Runnable retry;

    synchronized Runnable begin() {
        active++;
        return this::finish;
    }

    synchronized boolean defer(Runnable resume, int count) {
        if (active == 0 || resume == null) return false;
        if (deferred == null) StatusReporter.info("Waiting for SAP code generation before compiling " + count + " Java source files");
        deferred = resume;
        return true;
    }

    void accepted(Runnable retryHeldSources) {
        synchronized (this) {
            if (active > 0) {
                retry = retryHeldSources;
                return;
            }
        }
        retryHeldSources.run();
    }

    private void finish() {
        Runnable next;
        synchronized (this) {
            if (--active != 0) return;
            // A deferred save already advances the source generation. Retrying it
            // again on success would compile a genuinely broken caller twice.
            next = deferred != null ? deferred : retry;
            deferred = null;
            retry = null;
        }
        if (next != null) next.run();
    }
}
