/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which SAP Commerce line the server is, said out loud.
 *
 * <p>Two lines are in the field at the same time. SAP shipped 2211-jdk21 in
 * September 2025, moving the platform to Java 21 and Spring 6.2, and from
 * 31 August 2026 builds on the Java 17 line are blocked, so every installation
 * is mid-migration or just past it. The two differ enough that it is the first
 * question worth asking about any report, and the agent was answering
 * "Platform: Hybris (auto-detected)" while the answer sat in
 * {@code bin/platform/build.number} two directories away.
 */
class PlatformVersionTest {

    @Test
    void theVersionIsReadFromTheBuildNumber(@TempDir Path home) throws Exception {
        writeBuildNumber(home, """
                #Build Number
                version=2211-jdk21.8
                version.api=2211-jdk21
                """);

        assertEquals("2211-jdk21.8", versionOf(home),
                "this is the line that tells a jdk21 platform from the Java 17 one");
    }

    @Test
    void theOlderLineReadsAsItself(@TempDir Path home) throws Exception {
        writeBuildNumber(home, "version=2211.34\nversion.api=2211\n");

        assertEquals("2211.34", versionOf(home));
    }

    /**
     * A missing or unreadable file is not a reason to fail a startup, and not a
     * reason to invent a version either: an installation this cannot describe
     * should say so, so nobody reads a guess as a measurement.
     */
    @Test
    void anUnreadableBuildNumberSaysSoRatherThanGuessing(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("bin").resolve("platform"));

        String version = versionOf(home);
        assertTrue(version.startsWith("unknown"), version);
        assertFalse(version.contains("2211"), "a guess here would be worse than a blank");
    }

    @Test
    void aBuildNumberWithoutAVersionIsNotMistakenForOne(@TempDir Path home) throws Exception {
        writeBuildNumber(home, "#Build Number\nversion.api=2211-jdk21\n");

        assertTrue(versionOf(home).startsWith("unknown"),
                "version.api is not the build version, and reporting it as one would be wrong");
    }

    private static void writeBuildNumber(Path home, String contents) throws IOException {
        Path platform = home.resolve("bin").resolve("platform");
        Files.createDirectories(platform);
        Files.writeString(platform.resolve("build.number"), contents);
    }

    private static String versionOf(Path home) throws Exception {
        HybrisPlatformContext context = new HybrisPlatformContext(home, AgentConfig.parse(""));
        Method m = HybrisPlatformContext.class.getDeclaredMethod("platformVersion");
        m.setAccessible(true);
        return (String) m.invoke(context);
    }
}
