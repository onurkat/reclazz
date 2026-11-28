/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform.codegen;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.StatusReporter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Recoverable publication of staged SAP bootstrap files. Caller owns the installation's publication lock. */
public final class GeneratedPublication {
    private static final String MISSING = "-";
    private final Path bootstrap;
    private final Path journal;

    GeneratedPublication(Path platform, Path journal) {
        this.bootstrap = platform.resolve("bootstrap").toAbsolutePath().normalize();
        this.journal = journal;
    }

    static GeneratedPublication installation(Path platform) {
        return new GeneratedPublication(platform, platform.resolve(".reclazz-codegen/pending"));
    }

    /** Complete crash recovery before the agent starts watching either generated root. */
    public static void recoverAtStartup(Path platform) throws IOException {
        GeneratedPublication publication = installation(platform);
        if (Files.notExists(publication.journal.getParent(), LinkOption.NOFOLLOW_LINKS)) return;
        try (var lease = publication.acquire()) {
            publication.recover();
        }
    }

    /** The lock file remains in place so all processes synchronize on the same inode. */
    Lease acquire() throws IOException {
        Path directory = journal.getParent();
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            var attributes = directory.getFileSystem().supportedFileAttributeViews().contains("posix")
                    ? new java.nio.file.attribute.FileAttribute<?>[]{
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))}
                    : new java.nio.file.attribute.FileAttribute<?>[0];
            try { Files.createDirectory(directory, attributes); }
            catch (java.nio.file.FileAlreadyExistsException concurrent) { /* Validate the winner below. */ }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid generated publication directory");
        var channel = java.nio.channels.FileChannel.open(directory.resolve("writer.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.tryLock() == null) throw new IOException("Another generated publication is active");
            return new Lease(channel);
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            channel.close();
            throw new IOException("Another generated publication is active", busy);
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    record Lease(java.nio.channels.FileChannel channel) implements AutoCloseable {
        @Override public void close() throws IOException { channel.close(); }
    }

    /** A null staged file means removal. The digest observed during selection prevents publishing over another build. */
    public record Change(Path relative, String before, Path staged) { }

    record Prepared(Change change, String after) { }

    void publish(List<Change> changes) throws IOException {
        List<Prepared> prepared = prepare(changes);
        try {
            for (Prepared file : prepared) publishFile(file);
        } catch (IOException | RuntimeException failure) {
            try { recover(); }
            catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        retire();
    }

    void publishFile(Prepared file) throws IOException {
        Change change = file.change();
        Path target = target(change.relative());
        if (!digest(target).equals(change.before())) throw new IOException("Concurrent generated output change");
        replace(change.staged(), target, file.after());
        if (!digest(target).equals(file.after())) throw new IOException("Generated output verification failed");
    }

    List<Prepared> prepare(List<Change> changes) throws IOException {
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("An earlier generated publication still requires recovery");
        }
        Files.createDirectories(journal.getParent());
        Path preparation = Files.createTempDirectory(journal.getParent(), "prepare-");
        var manifest = new Properties();
        var prepared = new ArrayList<Prepared>();
        var unique = new java.util.HashSet<Path>();
        try {
            for (int i = 0; i < changes.size(); i++) {
                Change change = changes.get(i);
                Path target = target(change.relative());
                if (!unique.add(target)) throw new IOException("Duplicate output: " + change.relative());
                String before = digest(target);
                if (!before.equals(change.before())) throw new IOException("Generated baseline changed: " + change.relative());
                if (!before.equals(MISSING)) {
                    Path backup = preparation.resolve(i + ".old");
                    Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
                    if (!digest(backup).equals(before)) throw new IOException("Generated backup verification failed");
                }
                String after = change.staged() == null ? MISSING : digest(change.staged());
                if (change.staged() != null && after.equals(MISSING)) throw new IOException("Missing staged output");
                manifest.setProperty(i + ".path", change.relative().toString());
                manifest.setProperty(i + ".old", before);
                manifest.setProperty(i + ".new", after);
                prepared.add(new Prepared(change, after));
            }
            manifest.setProperty("count", String.valueOf(changes.size()));
            try (var out = Files.newOutputStream(preparation.resolve("manifest"))) {
                manifest.store(out, "Reclazz generated publication");
            }
            // No live file has been touched. Only a complete set of backups can become recoverable.
            Files.move(preparation, journal, StandardCopyOption.ATOMIC_MOVE);
            return prepared;
        } finally {
            if (Files.exists(preparation)) deleteTree(preparation);
        }
    }

    void recover() throws IOException {
        if (Files.notExists(journal, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(journal, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid generated recovery journal");
        Properties manifest = new Properties();
        try (InputStream in = Files.newInputStream(journal.resolve("manifest"))) { manifest.load(in); }
        int count;
        try { count = Integer.parseInt(manifest.getProperty("count")); }
        catch (NumberFormatException e) { throw new IOException("Invalid generated recovery manifest", e); }
        if (count < 0 || manifest.size() != 1L + 3L * count) throw new IOException("Incomplete generated recovery manifest");
        var pending = new ArrayList<Change>();
        for (int i = 0; i < count; i++) {
            Path relative = Path.of(manifest.getProperty(i + ".path"));
            String before = manifest.getProperty(i + ".old");
            String current = digest(target(relative));
            if (current.equals(before)) continue; // untouched entry or an earlier recovery already restored it
            if (!current.equals(manifest.getProperty(i + ".new"))) throw new IOException("Generated file changed independently: " + relative);
            Path backup = before.equals(MISSING) ? null : journal.resolve(i + ".old");
            if (backup != null && !before.equals(digest(backup))) throw new IOException("Damaged generated backup: " + relative);
            pending.add(new Change(relative, before, backup));
        }
        for (Change change : pending) {
            Path target = target(change.relative());
            replace(change.staged(), target, change.before());
            if (!digest(target).equals(change.before())) throw new IOException("Generated recovery verification failed");
        }
        retire();
    }

    private void retire() throws IOException {
        // A crash during cleanup must never leave a partial recovery manifest.
        Path retired = journal.resolveSibling("retired-" + java.util.UUID.randomUUID());
        Files.move(journal, retired, StandardCopyOption.ATOMIC_MOVE);
        // The rename commits the verified generation or rollback. Only redundant backups remain here.
        try { deleteTree(retired); }
        catch (IOException | java.io.UncheckedIOException cleanup) {
            StatusReporter.warn("Could not remove retired generated backups: " + Failures.describe(cleanup));
        }
    }

    private Path target(Path relative) throws IOException {
        if (relative.isAbsolute() || !relative.normalize().equals(relative)
                || !(relative.equals(Path.of("bin", "models.jar"))
                || relative.startsWith("gensrc") && relative.toString().endsWith(".java")
                || relative.startsWith("modelclasses") && relative.toString().endsWith(".class"))) {
            throw new IOException("Invalid generated output: " + relative);
        }
        Path target = bootstrap.resolve(relative);
        for (Path p = target; p != null && p.startsWith(bootstrap); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("Redirected generated output: " + relative);
        }
        return target;
    }

    private static void replace(Path source, Path target, String expected) throws IOException {
        if (source == null) { Files.deleteIfExists(target); return; }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".reclazz-", ".tmp");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            if (!digest(tmp).equals(expected)) throw new IOException("Source changed while copying generated output");
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    public static String digest(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return MISSING;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular generated file: " + path);
        try (var in = new DigestInputStream(Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static void deleteTree(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
