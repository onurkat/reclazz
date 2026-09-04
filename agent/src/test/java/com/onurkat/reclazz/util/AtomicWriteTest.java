/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replacing a file without ever leaving half of one.
 *
 * <p>{@code Files.writeString} truncates the target and then writes into it,
 * so between those two things the file is empty and, if anything goes wrong in
 * between, it stays that way. The two files this matters for are somebody
 * else's SAP Commerce configuration, where a truncated {@code wrapper.conf} is
 * a server that will not start, and the port file, which the IDE polls with no
 * lock between the two processes: a reader arriving mid-write gets whatever
 * prefix has landed, and "586" is a perfectly parseable port number that
 * nothing is listening on.
 */
class AtomicWriteTest {

    @TempDir
    Path tmp;

    @Test
    void theContentLands() throws IOException {
        Path target = tmp.resolve("95-local.properties");

        AtomicWrite.string(target, "tomcat.javaoptions=-javaagent:reclazz\n");

        assertEquals("tomcat.javaoptions=-javaagent:reclazz\n",
                Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void anExistingFileIsReplacedWhole() throws IOException {
        Path target = tmp.resolve("wrapper.conf");
        Files.writeString(target, "wrapper.java.additional.26=old\n");

        AtomicWrite.string(target, "wrapper.java.additional.26=new\n");

        assertEquals("wrapper.java.additional.26=new\n", Files.readString(target));
    }

    /** A temporary file left in somebody's config directory is its own mess. */
    @Test
    void nothingIsLeftBehind() throws IOException {
        AtomicWrite.string(tmp.resolve("local.properties"), "a=1\n");
        AtomicWrite.string(tmp.resolve("local.properties"), "a=2\n");

        assertEquals(List.of("local.properties"), namesIn(tmp));
    }

    /**
     * The point of the whole thing. When the write cannot be completed, the
     * file that was there is the file that is still there: not an empty one,
     * and not a partial one.
     */
    @Test
    void afailedWriteLeavesTheOriginalUntouched() throws IOException {
        // A directory where the file should be: the move cannot replace it, so
        // this fails at the last step, which is the step that matters.
        Path target = tmp.resolve("wrapper.conf");
        Files.createDirectory(target);
        Files.writeString(target.resolve("inside"), "still here\n");

        assertThrows(IOException.class,
                () -> AtomicWrite.string(target, "wrapper.java.additional.26=new\n"));

        assertTrue(Files.isDirectory(target), "the target was not replaced by half a write");
        assertEquals("still here\n", Files.readString(target.resolve("inside")));
        assertEquals(List.of("wrapper.conf"), namesIn(tmp),
                "and the temporary file was cleaned up on the way out");
    }

    @Test
    void aPortFileIsWrittenWholeOrNotAtAll() throws IOException {
        Path portFile = tmp.resolve("agent.port");

        AtomicWrite.string(portFile, "58619");

        assertEquals("58619", Files.readString(portFile),
                "the IDE parses this without a lock, so a prefix of it is a wrong answer "
                        + "rather than a failed read");
    }

    /**
     * The property the whole thing exists for, watched rather than argued
     * about. A reader with no lock between it and the writer must see either
     * the old file or the new one, and never the space between them.
     *
     * <p>Measured against what this replaced, on this machine and at exactly
     * this size and count: {@code Files.writeString} was caught mid-flight by 7
     * and then 9 of 22 reads. Here it has to be none of them.
     */
    @Test
    void aReaderNeverSeesHalfOfIt() throws Exception {
        String before = "a".repeat(64_000);
        String after = "b".repeat(64_000);
        Path target = tmp.resolve("wrapper.conf");
        AtomicWrite.string(target, before);

        java.util.concurrent.atomic.AtomicInteger torn =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean();

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    String seen = Files.readString(target);
                    reads.incrementAndGet();
                    if (!seen.equals(before) && !seen.equals(after)) torn.incrementAndGet();
                } catch (IOException between) {
                    // A read that lands exactly on the rename is allowed to
                    // fail; what it must never do is succeed with half a file.
                }
                // Not a spin: this runs beside a child-JVM load test that
                // measures overlap, and starving that machine failed it once.
                try {
                    Thread.sleep(0, 200_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 60; i++) {
                AtomicWrite.string(target, i % 2 == 0 ? after : before);
            }
        } finally {
            stop.set(true);
            reader.join(5000);
        }

        assertTrue(reads.get() > 0, "the reader never got to look, so this proved nothing");
        assertEquals(0, torn.get(),
                () -> torn.get() + " of " + reads.get() + " reads saw a file that was neither "
                        + "the old one nor the new one");
    }

    /**
     * The permissions belong to the file, not to how it was replaced.
     * {@code createTempFile} makes an owner-only file, and on the SAP Commerce
     * installation this was measured against, {@code wrapper.conf} is 777:
     * handing it back as 600 would leave a server another account starts unable
     * to read its own configuration.
     */
    @Test
    void theFilesOwnPermissionsSurviveTheReplacement() throws IOException {
        Path target = tmp.resolve("wrapper.conf");
        Files.writeString(target, "old\n");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> wide =
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx");
        try {
            Files.setPosixFilePermissions(target, wide);
        } catch (UnsupportedOperationException notPosix) {
            return;   // nothing to preserve here, and nothing to check
        }

        AtomicWrite.string(target, "new\n");

        assertEquals(wide, Files.getPosixFilePermissions(target),
                "the replacement handed the file back locked to its owner");
    }

    /** And a file that did not exist gets the ordinary one, not the temp file's. */
    @Test
    void aNewFileIsNotOwnerOnly() throws IOException {
        Path target = tmp.resolve("95-local.properties");

        AtomicWrite.string(target, "a=1\n");

        try {
            assertEquals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"),
                    Files.getPosixFilePermissions(target));
        } catch (UnsupportedOperationException notPosix) {
            // Windows has nothing to say about this.
        }
    }

    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
