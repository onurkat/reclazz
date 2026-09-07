/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.config.AgentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * On a machine whose default locale is Turkish, the capital I lowers to a
 * dotless ı. Everything the agent compares after lowering is ASCII it wrote
 * itself, so the comparison has to lower with Locale.ROOT; where it did
 * not, a JetBrains Runtime was not recognised and platform=HYBRIS was not
 * hybris.
 */
class TurkishLocaleTest {

    private Locale before;

    @BeforeEach
    void turkish() {
        before = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        assertEquals("jetbra\u0131ns", "JETBRAINS".toLowerCase(), "the trap this test is about is real here");
    }

    @AfterEach
    void restore() {
        Locale.setDefault(before);
    }

    @Test
    void aJetBrainsRuntimeIsStillRecognised() {
        assertEquals("JBR", JvmCapabilityProbe.detectVmIdentity("OpenJDK 64-Bit Server VM", "JetBrains s.r.o.", "21.0.5+8-b631.16"));
        assertEquals("JBR", JvmCapabilityProbe.detectVmIdentity("JetBrains Runtime", "", ""));
        assertEquals("DCEVM", JvmCapabilityProbe.detectVmIdentity("Dynamic Code Evolution 64-Bit Server VM", "", "DCEVM"));
        assertNull(JvmCapabilityProbe.detectVmIdentity("SapMachine", "SAP SE", "21"));
    }

    @Test
    void anUpperCasePlatformArgumentStillMatches() {
        assertEquals("hybris", AgentConfig.parse("platform=HYBRIS").getPlatform());
        assertEquals("auto", AgentConfig.parse("wrapOutput=AUTO").getWrapOutput());
    }
}
