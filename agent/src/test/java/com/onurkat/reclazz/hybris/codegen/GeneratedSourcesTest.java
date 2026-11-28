/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedSourcesTest {
    @TempDir Path dir;

    @Test void onlyTheNativeHeaderTimestampIsIgnored() throws IOException {
        Path file = dir.resolve("Dto.java");
        String date = "Jan 3, 2026, 1:09:11\u202fPM";
        String banner = "/*\r\n * --- WARNING: THIS FILE IS GENERATED AND WILL BE OVERWRITTEN!\r\n"
                + " * --- Generated at " + date + "\r\n */\r\n";
        String body = "/*\n * --- Generated at " + date + "\n */\nclass Dto {}\n";
        Files.writeString(file, banner + body);
        var baseline = GeneratedSources.inventory(dir);
        Files.writeString(file, banner.replace(date, "Nov 28, 2026, 6:05:09 AM") + body);
        assertEquals(List.of(), GeneratedSources.changes(baseline, GeneratedSources.inventory(dir)));
        Files.writeString(file, banner + body.replace(date, "Nov 28, 2026, 6:05:09 AM"));
        assertEquals(List.of(Path.of("Dto.java")), GeneratedSources.changes(baseline, GeneratedSources.inventory(dir)));
    }

    @Test void nativeBannerAfterACopyrightCommentIgnoresOnlyItsTimestamp() throws IOException {
        Path file = dir.resolve("Dto.java");
        String notice = "/* Copyright (c) Example */\n";
        String header = "/*\n * --- WARNING: THIS FILE IS GENERATED AND WILL BE OVERWRITTEN!\n"
                + " * --- Generated at Nov 28, 2026, 2:56:39 AM\n */\n";
        String body = "class Dto {}\n";
        Files.writeString(file, notice + header + body);
        var baseline = GeneratedSources.inventory(dir);
        Files.writeString(file, notice + header.replace("2:56:39", "2:57:00") + body);
        assertEquals(baseline, GeneratedSources.inventory(dir));
        Files.writeString(file, notice.replace("Example", "Different") + header + body);
        assertNotEquals(baseline, GeneratedSources.inventory(dir));
    }

    @Test void newSourcesAreSelectedAndRemovalsRequireTheFullBuild() throws IOException {
        Path dto = Path.of("Dto.java");
        assertEquals(List.of(dto), GeneratedSources.changes(Map.of(), Map.of(dto, "digest")));
        assertThrows(IOException.class, () -> GeneratedSources.changes(Map.of(dto, "digest"), Map.of()));
    }

    @Test void fiftyChangesAreAllowedAndFiftyOneRequireTheFullBuild() throws IOException {
        var next = new LinkedHashMap<Path, String>();
        for (int i = 0; i < 50; i++) next.put(Path.of("Dto" + i + ".java"), "digest");
        assertEquals(50, GeneratedSources.changes(Map.of(), next).size());
        next.put(Path.of("Another.java"), "digest");
        assertThrows(IOException.class, () -> GeneratedSources.changes(Map.of(), next));
    }
}
