/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The facade call itself needs a running SAP Commerce and is verified live; what is
 * unit-testable is how its result map is read. A completed update reports no failure and,
 * on the versions that set one, a success flag; a flagged failure or a non-map result is
 * not a success. Reading this wrong would either claim an update that did not happen or
 * keep nagging after one that did.
 */
class HacRunningSystemUpdaterTest {

    private static boolean succeeded(Object result) {
        return HacRunningSystemUpdater.succeeded(result);
    }

    @Test void aNonMapResultIsNotSuccess() {
        assertFalse(succeeded(null));
        assertFalse(succeeded("done"));
    }

    @Test void anExplicitFailureIsNotSuccess() {
        assertFalse(succeeded(Map.of("Failed", Boolean.TRUE)));
        assertFalse(succeeded(Map.of("Failed", "true", "success", Boolean.TRUE)),
                "a flagged failure wins even if a success flag is also present");
    }

    @Test void anExplicitSuccessFlagIsHonoured() {
        assertTrue(succeeded(Map.of("success", Boolean.TRUE)));
        assertTrue(succeeded(Map.of("success", "true")));
        assertFalse(succeeded(Map.of("success", Boolean.FALSE)));
    }

    @Test void aMapWithNoFailureFlagIsTreatedAsComplete() {
        assertTrue(succeeded(Map.of("log", "updated types")));
        Map<String, Object> blankFailure = new HashMap<>();
        blankFailure.put("Failed", "false");
        assertTrue(succeeded(blankFailure), "a Failed=false string is not a failure");
    }
}
