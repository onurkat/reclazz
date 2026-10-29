/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ext;

/** The single explicitly named optional module implements this core contract. */
@FunctionalInterface
public interface PremiumModuleFactory {
    PremiumModule create(PremiumContext context);
}
