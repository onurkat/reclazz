/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedBaselineTest {
    @TempDir Path bootstrap;

    @Test void sameSizeAndTimestampDoNotHideAnExternalClassChange() throws IOException {
        Files.createDirectories(bootstrap.resolve("gensrc"));
        Path classes = Files.createDirectories(bootstrap.resolve("modelclasses/p"));
        Path model = Files.write(classes.resolve("Dto.class"), new byte[]{1, 2, 3});
        Path jar = Files.createDirectories(bootstrap.resolve("bin")).resolve("models.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("p/Dto.class")); Files.copy(model, zip); zip.closeEntry();
        }
        var baseline = GeneratedBaseline.capture(bootstrap);
        assertEquals(1, baseline.archivedClasses().size());
        var stamp = Files.getLastModifiedTime(model);
        Files.write(model, new byte[]{3, 2, 1}); Files.setLastModifiedTime(model, stamp);
        assertThrows(IOException.class, () -> GeneratedBaseline.capture(bootstrap));
    }

    @Test void nextBaselineUsesStagedContentAndPreservesUnrelatedSourceIdentity() throws IOException {
        Path sources = Files.createDirectories(bootstrap.resolve("gensrc"));
        Path unchanged = Files.writeString(sources.resolve("Unrelated.java"), "class Unrelated {}");
        Path models = Files.createDirectories(bootstrap.resolve("modelclasses"));
        Path jar = Files.createDirectories(bootstrap.resolve("bin")).resolve("models.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) { zip.finish(); }
        var before = GeneratedBaseline.capture(bootstrap);
        Path stage = Files.createDirectory(bootstrap.resolve("stage"));
        Path source = Files.writeString(stage.resolve("Dto.java"), "class Dto {}");
        var sourceHashes = GeneratedSources.inventory(stage);
        Path added = Files.write(stage.resolve("Dto.class"), new byte[]{1, 2});
        Map<Path, Path> compiled = Map.of(Path.of("Dto.class"), added);
        Path stagedJar = stage.resolve("models.jar");
        StagedModelsJar.build(jar, stagedJar, compiled);
        var next = before.afterPublication(sourceHashes, compiled, stagedJar);
        Files.copy(source, sources.resolve("Dto.java"));
        Files.copy(added, models.resolve("Dto.class"));
        Files.copy(stagedJar, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals(next, GeneratedBaseline.capture(bootstrap));
        Files.writeString(unchanged, "class Unrelated { int changed; }");
        assertNotEquals(next, GeneratedBaseline.capture(bootstrap));
    }
}
