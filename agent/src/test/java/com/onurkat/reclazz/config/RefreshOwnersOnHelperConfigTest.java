/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The opt-in helper to cache-owner mapping parses, coalesces and ignores malformed entries. */
class RefreshOwnersOnHelperConfigTest {

    @Test void absentByDefault() {
        assertTrue(AgentConfig.parse("verbose=true").getRefreshOwnersOnHelper().isEmpty());
    }

    @Test void parsesHelpersOwnersAndMultipleMappings() {
        Map<String, List<String>> map = AgentConfig.parse(
                "refreshOwnersOnHelper=a.Helper>a.One;a.Two|b.Helper>b.Three").getRefreshOwnersOnHelper();
        assertEquals(List.of("a.One", "a.Two"), map.get("a.Helper"));
        assertEquals(List.of("b.Three"), map.get("b.Helper"));
        assertEquals(2, map.size());
    }

    @Test void duplicateOwnersAndRepeatedHelpersCoalesce() {
        Map<String, List<String>> map = AgentConfig.parse(
                "refreshOwnersOnHelper=a.Helper>a.One;a.One|a.Helper>a.Two").getRefreshOwnersOnHelper();
        assertEquals(List.of("a.One", "a.Two"), map.get("a.Helper"));
    }

    @Test void malformedEntriesAreSkippedNotFatal() {
        Map<String, List<String>> map = AgentConfig.parse(
                "refreshOwnersOnHelper=noArrow|>onlyOwners|a.Helper>;;a.One;").getRefreshOwnersOnHelper();
        assertEquals(List.of("a.One"), map.get("a.Helper"));
        assertEquals(1, map.size());
    }

    @Test void coexistsWithOtherArgumentsAcrossCommas() {
        AgentConfig config = AgentConfig.parse(
                "platform=generic,refreshOwnersOnHelper=a.Helper>a.One;a.Two,verbose=true");
        assertEquals(List.of("a.One", "a.Two"), config.getRefreshOwnersOnHelper().get("a.Helper"));
        assertTrue(config.isVerbose());
        assertTrue(config.getUnknownKeys().isEmpty());
    }

    @Test void returnedMapIsUnmodifiable() {
        Map<String, List<String>> map = AgentConfig.parse(
                "refreshOwnersOnHelper=a.Helper>a.One").getRefreshOwnersOnHelper();
        assertThrows(UnsupportedOperationException.class, () -> map.put("x", List.of()));
    }
}
