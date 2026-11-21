/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

/** Admission gate for the staged record implementation. Native admission also requires a proven save-scoped cohort. */
public final class RecordReloadPlan {
    public enum Route { NATIVE, STOCK }
    private RecordReloadPlan() { }

    /** Enabling a route requires its real container/retained-instance acceptance in the same change. */
    public static boolean ready(Route route) {
        return switch(java.util.Objects.requireNonNull(route)) {
            case NATIVE -> true;
            case STOCK -> false;
        };
    }
    static boolean canAdmitShape(Class<?> owner,byte[] incoming) {
        // Kind changes remain refused even after a route is enabled. Capability and whole-cohort
        // admission must be supplied by the enabling implementation, before opening either gate.
        // Readiness alone is never a save-specific capability/ownership/cohort proof.
        return false;
    }
}
