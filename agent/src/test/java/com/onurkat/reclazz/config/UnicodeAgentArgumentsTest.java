/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UnicodeAgentArgumentsTest {
    private static List<String> rawAndEncoded(String raw) {
        String encoded = "reclazz-utf8:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return List.of(raw, encoded);
    }

    @Test void encodedAndRawOptionsPreserveUnicodeText() {
        for (String value : rawAndEncoded("watchExtensions=Çalışma;東京")) {
            assertEquals(Set.of("Çalışma", "東京"), AgentConfig.parse(value).getWatchExtensions());
        }
    }

    @Test void encodedAndRawOptionsKeepTheOriginalPaths() {
        String first = "Alan Çalışma/classes";
        String second = "東京/classes";
        String port = "çalışma/port";
        Charset fileNames = Charset.forName(System.getProperty("sun.jnu.encoding"));
        assumeTrue(fileNames.newEncoder().canEncode(first + second + port),
                () -> "Sample paths cannot be represented by " + fileNames);
        for (String value : rawAndEncoded("watchDirs=" + first + ";" + second + ",portFile=" + port + ",verbose=true")) {
            AgentConfig config = AgentConfig.parse(value);
            assertEquals(List.of(Path.of(first), Path.of(second)), config.getWatchDirs());
            assertEquals(Path.of(port), config.getPortFile());
            assertTrue(config.isVerbose());
        }
    }

    @Test void malformedTransportDoesNotBecomeDefaultOptions() {
        for (String suffix : List.of("!", "_w", "")) {
            assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("reclazz-utf8:" + suffix));
        }
    }
}
