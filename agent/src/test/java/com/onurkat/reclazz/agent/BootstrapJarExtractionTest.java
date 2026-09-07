/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bootstrap loader reads the extracted jar lazily for the life of the
 * JVM, so where it is written decides who can put code on the bootstrap
 * class path. It has to be somewhere only this user can write, under a name
 * nobody can have prepared in advance.
 */
class BootstrapJarExtractionTest {

    private static final byte[] PAYLOAD = "not really a jar".getBytes();

    @Test
    void theJarLandsInAFreshOwnerOnlyDirectory() throws Exception {
        Path jar = ReclazzAgent.extractBootstrapJar(new ByteArrayInputStream(PAYLOAD));
        try {
            assertArrayEquals(PAYLOAD, Files.readAllBytes(jar), "the bytes are the bootstrap jar's");

            Path dir = jar.getParent();
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            assertEquals(tmp, dir.toRealPath().getParent(),
                    "a directory of its own directly under the temp directory");
            assertTrue(dir.getFileName().toString().startsWith("reclazz-"));

            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(dir);
                Set<PosixFilePermission> filePerms = Files.getPosixFilePermissions(jar);
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE), dirPerms, "nobody else may enter the directory");
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        filePerms, "nobody else may read or write the jar");
            }
        } finally {
            Files.deleteIfExists(jar);
            Files.deleteIfExists(jar.getParent());
        }
    }

    /**
     * The old location was {@code $TMPDIR/reclazz-bootstrap-<pid>.jar}, which
     * anyone on the machine could create first. Occupy it with something the
     * agent cannot write over, a directory, and extraction must not care.
     */
    @Test
    void aPreparedFileAtTheOldPredictablePathIsNeverTouched() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path squatted = tmp.resolve("reclazz-bootstrap-" + ProcessHandle.current().pid() + ".jar");
        // A JVM that was killed rather than exited left the old file behind,
        // and PIDs come round again, so the path may already hold one of ours.
        if (Files.isRegularFile(squatted)) Files.delete(squatted);
        Files.createDirectories(squatted);
        Path marker = squatted.resolve("planted");
        Files.writeString(marker, "planted");
        Path first = null, second = null;
        try {
            first = ReclazzAgent.extractBootstrapJar(new ByteArrayInputStream(PAYLOAD));
            second = ReclazzAgent.extractBootstrapJar(new ByteArrayInputStream(PAYLOAD));

            assertTrue(Files.isDirectory(squatted) && Files.exists(marker),
                    "the prepared path was left exactly as it was");
            assertNotEquals(first, second, "two extractions never share a path, so there is no race to win");
            assertNotEquals(squatted.toRealPath(), first.getParent().toRealPath());
        } finally {
            for (Path p : new Path[]{first, second}) {
                if (p != null) {
                    Files.deleteIfExists(p);
                    Files.deleteIfExists(p.getParent());
                }
            }
            Files.deleteIfExists(marker);
            Files.deleteIfExists(squatted);
        }
    }
}
