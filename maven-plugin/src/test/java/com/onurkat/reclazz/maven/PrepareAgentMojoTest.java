/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrepareAgentMojoTest {

    private final File jar = new File("/repo/reclazz-agent-1.3.0.jar");

    @Test
    void buildsFlagWithPlatformAndWatchDirs() {
        String arg = PrepareAgentMojo.buildAgentArg(jar, "spring", "/app/target/classes", null);
        assertEquals("-javaagent:" + jar.getAbsolutePath()
                + "=platform=spring,watchDirs=/app/target/classes", arg);
    }

    @Test
    void extraArgumentsAreAppended() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("verbose", "true");
        String arg = PrepareAgentMojo.buildAgentArg(jar, "spring", "/c", extra);
        assertTrue(arg.contains("platform=spring"), arg);
        assertTrue(arg.contains("watchDirs=/c"), arg);
        assertTrue(arg.endsWith("verbose=true"), arg);
    }

    @Test
    void bareFlagWhenNothingConfigured() {
        String arg = PrepareAgentMojo.buildAgentArg(jar, null, null, null);
        assertEquals("-javaagent:" + jar.getAbsolutePath(), arg);
    }
}
