/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.launchpath;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Resolves the jar part of a JVM startup option, independently of the encoding of its options. */
public final class AgentJarPath {
    private AgentJarPath() {}
    public static boolean windows() { return java.io.File.separatorChar == '\\'; }

    static boolean needsStaging(Path path) {
        return path.toString().chars().anyMatch(c -> c >= 128 || c == '=');
    }

    public static boolean requiresResolution(Path jar, String override) {
        return windows() && (needsStaging(jar.toAbsolutePath()) || override != null && !override.isBlank());
    }

    static Path directory(String override) throws IOException {
        Path path;
        try { path = Path.of(override); }
        catch (InvalidPathException e) { throw new IOException("Invalid reclazz.agentDir: " + override, e); }
        if (!path.isAbsolute() || needsStaging(path))
            throw new IOException("reclazz.agentDir must be an absolute ASCII directory without '=': " + path);
        return path.normalize();
    }

    public static Path forLaunch(Path source, String override) throws IOException {
        return forLaunch(source, override, windows(), WindowsStageTrust::privateDirectory);
    }

    @FunctionalInterface
    interface StageDirectory { Path resolve() throws IOException; }

    static Path forLaunch(Path source, String override, boolean windows, StageDirectory automatic) throws IOException {
        Path jar = source.toAbsolutePath();
        if (!windows || !needsStaging(jar) && (override == null || override.isBlank())) return jar;
        try {
            Path base = override == null || override.isBlank() ? automatic.resolve() : directory(override);
            return publish(base, jar);
        } catch (IOException | RuntimeException e) {
            throw new IOException("Cannot stage the Windows Java agent from " + jar
                    + ". Set reclazz.agentDir to an accessible ASCII directory without '='. " + e.getMessage(), e);
        }
    }

    /** A persisted service configuration can outlive this user and run with a different identity. */
    public static Path forService(Path stable, Path bundled, String override) throws IOException {
        return forService(stable, bundled, override, windows(), WindowsStageTrust::verifyServiceFile);
    }

    @FunctionalInterface
    interface ServiceFileCheck { void verify(Path file) throws IOException; }

    static Path forService(Path stable, Path bundled, String override, boolean windows, ServiceFileCheck trust) throws IOException {
        if (!windows || !needsStaging(stable) && (override == null || override.isBlank())) return stable;
        if (override == null || override.isBlank())
            throw new IOException("Ask an administrator to provision reclazz-agent.jar and set reclazz.agentDir to its administrator-controlled ASCII directory.");
        Path file = directory(override).resolve("reclazz-agent.jar");
        trust.verify(file);
        verify(file, digest(bundled));
        return file;
    }

    static byte[] digest(Path file) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        try (var in = Files.newInputStream(file); var stream = new java.security.DigestInputStream(in, md)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return md.digest();
    }

    private static void verify(Path file, byte[] hash) throws IOException {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || !MessageDigest.isEqual(hash, digest(file)))
            throw new IOException("Agent jar is missing or its SHA-256 differs from the bundled jar: " + file);
    }

    static Path publish(Path base, Path source) throws IOException {
        Files.createDirectories(base);
        if (!base.equals(base.toRealPath())) throw new IOException("Unexpected redirected staging directory: " + base);
        byte[] expected = digest(source);
        Path destination = base.resolve(HexFormat.of().formatHex(expected));
        Path jar = destination.resolve("reclazz-agent.jar");
        if (!Files.exists(destination, NOFOLLOW_LINKS)) {
            Path temp = Files.createTempDirectory(base, ".staging-");
            Path copy = temp.resolve("reclazz-agent.jar");
            try {
                Files.copy(source, copy);
                try (FileChannel channel = FileChannel.open(copy, StandardOpenOption.WRITE)) { channel.force(true); }
                verify(copy, expected);
                try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException concurrent) {
                    if (!Files.isDirectory(destination, NOFOLLOW_LINKS)) throw concurrent;
                }
            } finally {
                Files.deleteIfExists(copy);
                Files.deleteIfExists(temp);
            }
        }
        if (!destination.equals(destination.toRealPath())) throw new IOException("Unexpected redirected digest directory: " + destination);
        verify(jar, expected);
        return jar;
    }
}
