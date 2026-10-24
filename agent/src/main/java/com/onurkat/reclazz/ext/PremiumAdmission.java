/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

/** Admission is separate from the code that carries out an operation. */
public interface PremiumAdmission {
    Admission request(Capability capability);

    record Admission(boolean granted, String reason) { }
}
