/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** The available-extension name index. Active-extension metadata is always read from the installation. */
final class ExtensionIndexCache {
    private static final int MAX_ENTRIES = 250_000;

    interface NameReader {
        String read(Path manifest) throws Exception;
    }

    private final Path directory;
    private final String configuration;

    ExtensionIndexCache(Path directory, byte[] localExtensions, byte[] platformVersion) {
        this.directory = directory;
        MessageDigest digest = sha256();
        digest.update((byte) 2); // format includes the filesystem change time as well as mtime
        digest.update(sha256().digest(localExtensions));
        this.configuration = HexFormat.of().formatHex(digest.digest(platformVersion));
    }

    static ExtensionIndexCache forInstallation(Path platform, Path config) {
        try {
            Path directory = Path.of(System.getProperty("user.home"), ".reclazz", "extension-index");
            return new ExtensionIndexCache(directory, Files.readAllBytes(config.resolve("localextensions.xml")),
                    Files.readAllBytes(platform.resolve("build.number")));
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** A symlink, incomplete traversal or unavailable identity delegates to the ordinary link-following scan. */
    Map<String, Path> index(Path root, NameReader names) {
        if (!root.getFileSystem().supportedFileAttributeViews().contains("unix")) return null;
        Path file = directory.resolve(HexFormat.of().formatHex(sha256().digest(
                root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8))) + ".gz");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (configuration.equals(input.readUTF())) {
                int count = input.readInt();
                if (count > 0 && count <= MAX_ENTRIES) {
                    Map<String, Path> found = new HashMap<>();
                    for (int i = 0; i < count; i++) {
                        Stamp stamp = Stamp.read(input);
                        Path relative = root.getFileSystem().getPath(stamp.relative());
                        if (relative.isAbsolute() || relative.startsWith("..") || !relative.normalize().equals(relative)
                                || (i == 0 && (!stamp.relative().isEmpty() || !stamp.directory()))) throw new IOException("index path");
                        Path path = root.resolve(relative);
                        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (!stamp.matches(a, changeTime(path))) throw new IOException("index changed");
                        if (!stamp.directory()) found.put(stamp.name(), path.getParent());
                    }
                    if (input.read() == -1) return found;
                }
            }
        } catch (IOException | RuntimeException stale) {
            // Missing, stale or unreadable caches are an ordinary cold start.
        }

        List<Stamp> stamps = new ArrayList<>();
        Map<String, Path> found = new HashMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                    stamps.add(stamp(dir, a, ""));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes a) throws IOException {
                    if (a.isSymbolicLink()) throw new IOException("link needs ordinary traversal");
                    if (a.isRegularFile() && path.getFileName().toString().equals("extensioninfo.xml")) {
                        String name;
                        try {
                            name = names.read(path);
                        } catch (Exception unreadable) {
                            throw new IOException(unreadable);
                        }
                        if (name == null) throw new IOException("unindexable manifest");
                        stamps.add(stamp(path, a, name));
                        found.put(name, path.getParent());
                    }
                    return FileVisitResult.CONTINUE;
                }
                private Stamp stamp(Path path, BasicFileAttributes a, String name) throws IOException {
                    if (a.fileKey() == null || stamps.size() >= MAX_ENTRIES) throw new IOException("uncacheable tree");
                    return new Stamp(root.relativize(path).toString(), name, a.fileKey().toString(),
                            a.lastModifiedTime().to(TimeUnit.NANOSECONDS), changeTime(path), a.isDirectory() ? -1 : a.size());
                }
            });
        } catch (IOException | RuntimeException incomplete) {
            return null;
        }
        if (stamps.isEmpty() || !stamps.get(0).directory()) return null;
        persist(file, stamps);
        return found;
    }

    private void persist(Path target, List<Stamp> stamps) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".index-", ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                out.writeUTF(configuration);
                out.writeInt(stamps.size());
                for (Stamp stamp : stamps) stamp.write(out);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // The index from this scan is complete even if the cache cannot be saved.
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException | SecurityException ignored) { }
        }
    }

    // Copy and restore tools may preserve a directory's mtime after changing its entries.
    private static long changeTime(Path path) throws IOException {
        return ((FileTime) Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS)).to(TimeUnit.NANOSECONDS);
    }

    private record Stamp(String relative, String name, String identity, long modified, long changed, long size) {
        boolean directory() { return size == -1; }

        boolean matches(BasicFileAttributes a, long observedChange) {
            return a.fileKey() != null && identity.equals(a.fileKey().toString())
                    && changed == observedChange && modified == a.lastModifiedTime().to(TimeUnit.NANOSECONDS)
                    && (directory() ? a.isDirectory() : a.isRegularFile() && size == a.size());
        }

        void write(DataOutput out) throws IOException {
            out.writeUTF(relative);
            out.writeUTF(name);
            out.writeUTF(identity);
            out.writeLong(modified);
            out.writeLong(changed);
            out.writeLong(size);
        }

        static Stamp read(DataInput in) throws IOException {
            return new Stamp(in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(), in.readLong(), in.readLong());
        }
    }
}
