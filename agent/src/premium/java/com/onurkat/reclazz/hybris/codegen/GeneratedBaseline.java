/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import com.onurkat.reclazz.platform.codegen.GeneratedPublication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipFile;

/** Installation outputs as observed following a successful native build in this agent session. */
record GeneratedBaseline(Map<Path, String> sources, Map<Path, String> classes, Map<String, String> archivedClasses,
                         FileTime jarModified, long jarSize, String jarDigest) {
    static GeneratedBaseline capture(Path bootstrap) throws IOException {
        Path jar = bootstrap.resolve("bin/models.jar");
        var sources = GeneratedSources.inventory(bootstrap.resolve("gensrc"));
        Map<Path, String> classes = new HashMap<>();
        Path loose = bootstrap.resolve("modelclasses");
        try (var walk = Files.walk(loose)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(path)) throw new IOException("Redirected generated output");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!path.toString().endsWith(".class")) throw new IOException("Unknown modelclasses output");
                classes.put(loose.relativize(path), GeneratedPublication.digest(path));
            }
        }
        Map<String, String> entries = new HashMap<>();
        try (var archive = new ZipFile(jar.toFile())) {
            var all = archive.entries();
            while (all.hasMoreElements()) {
                var entry = all.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                String digest;
                try (var in = new DigestInputStream(archive.getInputStream(entry), sha256())) {
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                    digest = HexFormat.of().formatHex(in.getMessageDigest().digest());
                }
                if (entries.putIfAbsent(entry.getName(), digest) != null
                        || !digest.equals(classes.get(Path.of(entry.getName()))))
                    throw new IOException("Loose generated class and models.jar disagree");
            }
        }
        if (entries.size() != classes.size()) throw new IOException("Incomplete generated class archive");
        return new GeneratedBaseline(sources, Map.copyOf(classes), Map.copyOf(entries),
                Files.getLastModifiedTime(jar), Files.size(jar), GeneratedPublication.digest(jar));
    }

    /** Advance using the inspected staged bytes. Unrelated concurrent changes stay detectable on the next save. */
    GeneratedBaseline afterPublication(Map<Path, String> dtos, Map<Path, Path> compiled, Path stagedJar)
            throws IOException {
        var nextSources = new HashMap<>(sources);
        nextSources.putAll(dtos);
        var nextClasses = new HashMap<>(classes);
        var nextEntries = new HashMap<>(archivedClasses);
        for (var output : compiled.entrySet()) {
            String digest = GeneratedPublication.digest(output.getValue());
            Path path = output.getKey();
            nextClasses.put(path, digest);
            nextEntries.put(path.toString().replace(path.getFileSystem().getSeparator(), "/"), digest);
        }
        return new GeneratedBaseline(Map.copyOf(nextSources), Map.copyOf(nextClasses), Map.copyOf(nextEntries),
                Files.getLastModifiedTime(stagedJar), Files.size(stagedJar), GeneratedPublication.digest(stagedJar));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
