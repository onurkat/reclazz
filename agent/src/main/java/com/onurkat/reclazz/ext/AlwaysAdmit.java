/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

/** Phase 1 preserves all existing automation, with no license enforcement. */
public final class AlwaysAdmit implements PremiumAdmission {
    @Override public Admission request(Capability capability) {
        return new Admission(true, "");
    }
}
