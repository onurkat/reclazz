/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BuildResultTest {
    @Test fun onlySuccessfulCompilationReleasesOutput() {
        assertTrue(buildSucceeded(false, 0))
        assertFalse(buildSucceeded(false, 1))
        assertFalse(buildSucceeded(true, 0))
        assertFalse(buildSucceeded(true, 5))
    }
}
