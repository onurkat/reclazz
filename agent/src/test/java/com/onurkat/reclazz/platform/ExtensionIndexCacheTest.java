/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExtensionIndexCacheTest {
    @TempDir Path tmp;

    @BeforeEach void stableFileIdentitiesAvailable() throws Exception {
        assumeTrue(tmp.getFileSystem().supportedFileAttributeViews().contains("unix"));
        assumeTrue(Files.readAttributes(tmp, java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null,
                "cache requires stable filesystem identities");
    }

    private Path modules() throws Exception { return Files.createDirectories(tmp.resolve("modules")); }
    private ExtensionIndexCache cache(int config, int version) {
        return new ExtensionIndexCache(tmp.resolve("index"), new byte[]{(byte) config}, new byte[]{(byte) version});
    }
    private Path manifest(String dir, String name) throws Exception {
        Path path = Files.createDirectories(modules().resolve(dir)).resolve("extensioninfo.xml");
        return Files.writeString(path, name);
    }
    private Map<String, Path> warm() throws Exception {
        return cache(1, 1).index(modules(), path -> { throw new AssertionError("valid cache reparsed " + path); });
    }

    @Test void aNewScannerReusesTheFreshIndexAndNestedCreationInvalidatesIt() throws Exception {
        Path old = manifest("group/old", "alpha").getParent();
        var initial = cache(1, 1).index(modules(), Files::readString);
        assertEquals(Map.of("alpha", old), initial);
        assertTrue(Files.isDirectory(tmp.resolve("index")));
        assertEquals(initial, warm());
        Path second = manifest("group/deep/new", "beta").getParent();
        assertEquals(Map.of("alpha", old, "beta", second), cache(1, 1).index(modules(), Files::readString));
        Files.delete(second.resolve("extensioninfo.xml"));
        assertEquals(initial, cache(1, 1).index(modules(), Files::readString));
    }

    @Test void manifestChangesAndConfigurationOrVersionChangesRebuild() throws Exception {
        Path file = manifest("one", "alpha");
        cache(1, 1).index(modules(), Files::readString);
        Files.writeString(file, "bravo");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        assertEquals(Map.of("bravo", file.getParent()), cache(1, 1).index(modules(), Files::readString));
        assertEquals(Map.of("config", file.getParent()), cache(2, 1).index(modules(), path -> "config"));
        assertEquals(Map.of("version", file.getParent()), cache(2, 2).index(modules(), path -> "version"));
    }

    @Test void restoredDirectoryTimestampStillDiscoversANewExtension() throws Exception {
        Path group = manifest("group/old", "alpha").getParent().getParent();
        FileTime previous = Files.getLastModifiedTime(group);
        cache(1, 1).index(modules(), Files::readString);
        Path second = manifest("group/new", "beta").getParent();
        Files.setLastModifiedTime(group, previous);
        assertEquals(second, cache(1, 1).index(modules(), Files::readString).get("beta"));
    }

    @Test void truncatedCacheRebuildsFromTheFilesystem() throws Exception {
        Path only = manifest("one", "alpha").getParent();
        var expected = cache(1, 1).index(modules(), Files::readString);
        assertTrue(Files.isDirectory(tmp.resolve("index")));
        try (var files = Files.list(tmp.resolve("index"))) {
            Files.write(files.findFirst().orElseThrow(), new byte[]{1, 2, 3});
        }
        assertEquals(Map.of("alpha", only), expected);
        assertEquals(expected, cache(1, 1).index(modules(), Files::readString));
        assertEquals(expected, warm());
    }

    @Test void symlinkedRootsUseTheExistingLinkFollowingScan() throws Exception {
        Path original = manifest("one", "alpha").getParent();
        cache(1, 1).index(modules(), Files::readString);
        Path alias = modules().resolve("alias");
        try { Files.createSymbolicLink(alias, original); }
        catch (UnsupportedOperationException | java.io.IOException denied) {
            assumeTrue(false, "symlinks unavailable on this filesystem");
        }
        assertNull(cache(1, 1).index(modules(), Files::readString));
    }
}
