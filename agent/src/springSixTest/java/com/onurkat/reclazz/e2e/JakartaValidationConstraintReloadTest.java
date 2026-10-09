/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

/** Same HTTP, warmed-provider and old-validator observations on native Jakarta validation. */
class JakartaValidationConstraintReloadTest extends ValidationConstraintReloadTest {
    @Override String validationNamespace() { return "jakarta.validation"; }
}
