/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform.codegen;

import com.onurkat.reclazz.agent.ReloadQueue;
import com.onurkat.reclazz.watcher.ChangeEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedBuildTransactionTest {
    @TempDir Path platform;

    @Test void fallbackSeesOnlyTheRestoredGenerationAndRunsOnceUnderTheLock() throws IOException {
        Path dir = Files.createDirectories(platform.resolve("bootstrap/modelclasses/p"));
        Path original = Files.writeString(dir.resolve("A.class"), "old");
        var path = Path.of("modelclasses/p/A.class");
        var staged = Files.writeString(platform.resolve("new"), "new");
        var publication = GeneratedPublication.installation(platform);
        var count = new AtomicInteger();
        var result = GeneratedBuildTransaction.run(publication, () -> {
            var change = new GeneratedPublication.Change(path, GeneratedPublication.digest(original), staged);
            publication.publishFile(publication.prepare(List.of(change)).get(0));
            throw new IOException("injected after first publish");
        }, () -> {
            assertEquals("old", assertDoesNotThrow(() -> Files.readString(original)));
            assertThrows(IOException.class, publication::acquire);
            count.incrementAndGet();
            return false;
        });
        assertEquals(1, count.get());
        assertFalse(result.success());
        assertEquals("injected after first publish", result.fullReason());
        assertEquals(0, result.fastClasses());
    }

    @Test void watcherAdmitsOnlyTheFullBuildAfterAnInterruptedPublication() throws Exception {
        Path modelRoot = Files.createDirectories(platform.resolve("bootstrap/modelclasses"));
        Path original = Files.writeString(modelRoot.resolve("A.class"), "old");
        var publication = GeneratedPublication.installation(platform);
        var observed = new CopyOnWriteArrayList<String>();
        var queue = ReloadQueue.start(event -> observed.add(new String(event.getBytes(), StandardCharsets.UTF_8)),
                new ReloadQueue.Bracket() {
                    public void begin() { }
                    public void end() { }
                });
        var changeEvent = new ChangeEvent(original, ChangeEvent.Type.MODIFIED, "demo", "classes");
        var hold = queue.holdGeneratedClasses(List.of(modelRoot), () -> queue.enqueueClassFile(changeEvent));
        try {
            var outcome = GeneratedBuildTransaction.run(publication, () -> {
                var change = new GeneratedPublication.Change(Path.of("modelclasses/A.class"),
                        GeneratedPublication.digest(original), Files.writeString(platform.resolve("staged"), "partial"));
                publication.publishFile(publication.prepare(List.of(change)).get(0));
                queue.enqueueClassFile(changeEvent);
                queue.submitClassBatch(1);
                throw new IOException("interrupted between two outputs");
            }, () -> {
                assertTrue(observed.isEmpty(), observed.toString());
                assertEquals("old", assertDoesNotThrow(() -> Files.readString(original)));
                assertDoesNotThrow(() -> Files.writeString(original, "native full"));
                return true;
            });
            assertTrue(outcome.success());
            assertEquals(0, outcome.fastClasses());
            assertEquals("interrupted between two outputs", outcome.fullReason());
            hold.compiled(() -> { }).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("native full"), observed);
        } finally {
            queue.shutdown();
        }
    }

    @Test void anUnreadableClassDeclarationCannotProduceAFastPathReceipt() throws IOException {
        var fullRuns = new AtomicInteger();
        var result = GeneratedBuildTransaction.run(platform, () -> {
            throw new IllegalArgumentException();
        }, () -> fullRuns.incrementAndGet() == 1);
        assertEquals(1, fullRuns.get());
        assertTrue(result.success());
        assertEquals(0, result.fastClasses());
        assertEquals(IllegalArgumentException.class.getName(), result.fullReason());
    }

    @Test void cleanupAfterVerifiedRecoveryDoesNotPreventNativeFallback() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(platform.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var publication = GeneratedPublication.installation(platform);
        Path journal = platform.resolve(".reclazz-codegen/pending");
        var readOnly = java.nio.file.attribute.PosixFilePermissions.fromString("r-x------");
        var writable = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
        Path retained = Files.createDirectory(platform.resolve("fixture-retained"));
        try {
            Files.setPosixFilePermissions(retained, readOnly);
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(retained));
            var result = GeneratedBuildTransaction.run(publication, () -> {
                publication.prepare(List.of());
                Path restricted = Files.createDirectory(journal.resolve(retained.getFileName()));
                Files.writeString(restricted.resolve("locked"), "fixture");
                Files.setPosixFilePermissions(restricted, readOnly);
                throw new IOException("injected before publication");
            }, () -> true);
            assertTrue(result.success());
            assertEquals("injected before publication", result.fullReason());
            assertTrue(Files.notExists(journal));
        } finally {
            try (var files = Files.walk(platform)) {
                for (var path : files.filter(p -> p.endsWith("fixture-retained")).toList()) {
                    Files.setPosixFilePermissions(path, writable);
                }
            }
        }
    }

    @Test void damagedBackupPreventsAnyFallbackOverAnUnknownGeneration() throws IOException {
        Path dir = Files.createDirectories(platform.resolve("bootstrap/modelclasses/p"));
        Path live = Files.writeString(dir.resolve("A.class"), "old");
        var publication = GeneratedPublication.installation(platform);
        var count = new AtomicInteger();
        assertThrows(IOException.class, () -> GeneratedBuildTransaction.run(publication, () -> {
            var change = new GeneratedPublication.Change(Path.of("modelclasses/p/A.class"),
                    GeneratedPublication.digest(live), Files.writeString(platform.resolve("new"), "new"));
            publication.publishFile(publication.prepare(List.of(change)).get(0));
            Files.writeString(platform.resolve(".reclazz-codegen/pending/0.old"), "broken");
            throw new IOException("injected");
        }, () -> { count.incrementAndGet(); return true; }));
        assertEquals(0, count.get());
        assertTrue(Files.exists(platform.resolve(".reclazz-codegen/pending")));
    }
}
