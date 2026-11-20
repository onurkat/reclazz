/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

/** Admission gate for the staged record implementation. No route is enabled in C1. */
public final class RecordReloadPlan {
    public enum Route { NATIVE, STOCK }
    private RecordReloadPlan() { }

    /** Enabling a route requires its real container/retained-instance acceptance in the same change. */
    public static boolean ready(Route route) {
        return switch(java.util.Objects.requireNonNull(route)) {
            case NATIVE -> false;
            case STOCK -> false;
        };
    }
    static boolean canAdmitShape(Class<?> owner,byte[] incoming) {
        // Kind changes remain refused even after a route is enabled. Capability and whole-cohort
        // admission must be supplied by the enabling implementation, before opening either gate.
        return owner.isRecord() && RecordShapeCheck.incoming(incoming).record()
                && (ready(Route.NATIVE)||ready(Route.STOCK));
    }
}
