/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.bootstrap.RequestGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestReloadBoundaryTest {
    @Test
    void failedWorkAlwaysReopensAdmission() throws Exception {
        RequestGate.global().installed();
        Error original = new AssertionError("failed batch");
        assertSame(original, assertThrows(Error.class,
                () -> RequestReloadBoundary.run(() -> { throw original; })));
        assertTrue(RequestGate.global().tryBeginReload(10));
        RequestGate.global().endReload();
    }
}
