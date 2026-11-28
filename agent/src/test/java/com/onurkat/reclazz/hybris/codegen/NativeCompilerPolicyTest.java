/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeCompilerPolicyTest {
    @TempDir Path temp;
    @Test void checksTheCompleteOutputSetBeforeReturningAnyPublishableFile() throws IOException {
        Path output = Files.createDirectories(temp.resolve("com/example")).resolve("Dto.class");
        Files.write(output, new byte[]{1});
        var source = List.of(Path.of("com/example/Dto.java"));
        assertEquals(Map.of(Path.of("com/example/Dto.class"), output), NativeCompilerPolicy.expectedOutputs(temp, source));
        Path extra = Files.createDirectories(temp.resolve("META-INF/plugins")).resolve("generated.dat");
        Files.write(extra, new byte[]{2});
        assertThrows(IOException.class, () -> NativeCompilerPolicy.expectedOutputs(temp, source));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(output));
    }

    @Test void unexpectedClassAndMissingExpectedClassSelectTheFullBuild() throws IOException {
        var source = List.of(Path.of("Dto.java"));
        assertThrows(IOException.class, () -> NativeCompilerPolicy.expectedOutputs(temp, source));
        Files.write(temp.resolve("Dto.class"), new byte[]{1});
        Files.write(temp.resolve("Companion.class"), new byte[]{2});
        assertThrows(IOException.class, () -> NativeCompilerPolicy.expectedOutputs(temp, source));
    }
}
