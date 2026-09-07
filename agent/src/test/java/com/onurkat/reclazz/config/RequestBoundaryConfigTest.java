/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestBoundaryConfigTest {
    @Test
    void protectionIsOptInAndParsesBesideExistingArguments() {
        assertFalse(AgentConfig.parse(null).isRequestBoundary());
        assertFalse(AgentConfig.parse("reloadBoundary=immediate").isRequestBoundary());
        AgentConfig config = AgentConfig.parse("verbose=true,reloadBoundary=request,debounceMs=123");
        assertTrue(config.isRequestBoundary());
        assertTrue(config.isVerbose());
        assertEquals(123, config.getDebounceMs());
        assertTrue(config.getUnknownKeys().isEmpty());
    }

    @Test
    void misspellingTheModeDoesNotSilentlyDisableProtection() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("reloadBoundary=requset"));
    }
}
