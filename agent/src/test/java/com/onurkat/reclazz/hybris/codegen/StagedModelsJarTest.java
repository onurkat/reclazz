/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StagedModelsJarTest {
    @TempDir Path root;

    @Test void stagedJarContainsTheExactLooseBytesAndPreservesUnchangedEntries() throws Exception {
        Path original = root.resolve("models.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(original))) {
            for (String name : new String[]{"META-INF/MANIFEST.MF", "a/Old.class", "a/Other.class"}) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{0, 1, 2}); zip.closeEntry();
            }
        }
        byte[] before = Files.readAllBytes(original);
        Path replacement = Files.write(root.resolve("compiled"), new byte[]{3, 4});
        Path added = Files.write(root.resolve("added"), new byte[]{6});
        Path stage = root.resolve("stage.jar");
        StagedModelsJar.build(original, stage, Map.of(Path.of("a/Old.class"), replacement, Path.of("a/New.class"), added));
        try (var jar = new ZipFile(stage.toFile())) {
            assertArrayEquals(Files.readAllBytes(replacement), jar.getInputStream(jar.getEntry("a/Old.class")).readAllBytes());
            assertArrayEquals(Files.readAllBytes(added), jar.getInputStream(jar.getEntry("a/New.class")).readAllBytes());
            assertArrayEquals(new byte[]{0, 1, 2}, jar.getInputStream(jar.getEntry("META-INF/MANIFEST.MF")).readAllBytes());
            assertArrayEquals(new byte[]{0, 1, 2}, jar.getInputStream(jar.getEntry("a/Other.class")).readAllBytes());
            assertEquals(4, jar.size());
        }
        assertArrayEquals(before, Files.readAllBytes(original));
    }
}
