/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression: Hybris's Tanuki Wrapper sets HYBRIS_BIN_DIR as a -D system
 * property, not an env var. PlatformDetector must read both.
 */
class PlatformDetectorTest {

    @Test
    void v105_hybrisDetectedFromSystemProperty(@TempDir Path tmp) throws IOException {
        Path hybrisHome = tmp.resolve("hybris");
        Path platformDir = hybrisHome.resolve("bin").resolve("platform");
        Files.createDirectories(platformDir);
        Files.createDirectories(hybrisHome.resolve("config"));
        Files.writeString(hybrisHome.resolve("config").resolve("localextensions.xml"),
                "<hybrisconfig><extensions></extensions></hybrisconfig>");

        String prevSysprop = System.getProperty("HYBRIS_BIN_DIR");
        try {
            System.setProperty("HYBRIS_BIN_DIR", hybrisHome.resolve("bin").toString());
            PlatformContext ctx = PlatformDetector.detect(AgentConfig.parse(null));
            assertNotNull(ctx, "Platform context should be created");
            assertEquals(PlatformContext.Platform.HYBRIS, ctx.getPlatformId(),
                    "Should detect Hybris from system property");
        } finally {
            if (prevSysprop != null) {
                System.setProperty("HYBRIS_BIN_DIR", prevSysprop);
            } else {
                System.clearProperty("HYBRIS_BIN_DIR");
            }
        }
    }

    @Test
    void v105_noHybrisFallsBackToSpringBoot() {
        String prevSysprop = System.getProperty("HYBRIS_BIN_DIR");
        String prevPlatform = System.getProperty("platform.home");
        try {
            System.clearProperty("HYBRIS_BIN_DIR");
            System.clearProperty("platform.home");
            PlatformContext ctx = PlatformDetector.detect(AgentConfig.parse(null));
            assertNotNull(ctx);
            // Without sysprops/env, should fall through to SpringBoot context
            assertEquals(PlatformContext.Platform.SPRING_BOOT, ctx.getPlatformId());
        } finally {
            if (prevSysprop != null) System.setProperty("HYBRIS_BIN_DIR", prevSysprop);
            if (prevPlatform != null) System.setProperty("platform.home", prevPlatform);
        }
    }
}
