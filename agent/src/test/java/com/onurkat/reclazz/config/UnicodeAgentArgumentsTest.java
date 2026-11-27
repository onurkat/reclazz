/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnicodeAgentArgumentsTest {
    @Test void encodedAndRawOptionsKeepTheOriginalPaths() {
        String first = "Alan Çalışma/classes";
        String second = "東京/classes";
        String raw = "watchDirs=" + first + ";" + second + ",portFile=çalışma/port,verbose=true";
        String encoded = "reclazz-utf8:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        for (String value : List.of(raw, encoded)) {
            AgentConfig config = AgentConfig.parse(value);
            assertEquals(List.of(Path.of(first), Path.of(second)), config.getWatchDirs());
            assertEquals(Path.of("çalışma/port"), config.getPortFile());
            assertTrue(config.isVerbose());
        }
    }

    @Test void malformedTransportDoesNotBecomeDefaultOptions() {
        for (String suffix : List.of("!", "_w", "")) {
            assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("reclazz-utf8:" + suffix));
        }
    }
}
