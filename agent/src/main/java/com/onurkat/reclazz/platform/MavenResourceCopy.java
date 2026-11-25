/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** Unfiltered resources described by the Maven launcher. Existing output must match its launch provenance. */
final class MavenResourceCopy {
    enum Outcome { COPIED, UNCHANGED, EXCLUDED, MANUAL, DELETED }

    private record Root(Path source, Path target, boolean filtered, List<Pattern> includes, List<Pattern> excludes) {
        boolean selects(Path relative) {
            String name = relative.toString().replace(java.io.File.separatorChar, '/');
            return includes.stream().anyMatch(p -> p.matcher(name).matches())
                    && excludes.stream().noneMatch(p -> p.matcher(name).matches());
        }
    }
    private record Mapping(Root root, Path target) {}
    private record Output(Object fileKey, String hash) {
        boolean matches(Path path) throws IOException {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && Objects.equals(fileKey, key(path)) && hash.equals(digest(path));
        }
    }

    private final Path output;
    private final List<Root> roots;
    private final boolean overwrite;
    private final Map<Path, Output> owned = new HashMap<>();

    private MavenResourceCopy(Path output, List<Root> roots, boolean overwrite) {
        this.output = output;
        this.roots = roots;
        this.overwrite = overwrite;
    }

    static MavenResourceCopy load(Path file, Set<Path> classpath) throws IOException {
        var p = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        boolean manual = "advice".equals(p.getProperty("mode"));
        if (!manual && !"3.3.1".equals(p.getProperty("version"))) throw new IOException("Unverified Maven resource layout");
        Path output = manual ? null : Path.of(p.getProperty("output")).toRealPath();
        Set<Path> served = new HashSet<>();
        for (Path path : classpath) if (Files.isDirectory(path)) served.add(path.toRealPath());
        boolean copyToOutput = output != null && served.contains(output);
        var roots = new ArrayList<Root>();
        for (int i = 0; i < Integer.parseInt(p.getProperty("roots")); i++) {
            String prefix = "root." + i + ".";
            Path source = Path.of(p.getProperty(prefix + "path")).toAbsolutePath().normalize();
            if (Files.isDirectory(source)) source = source.toRealPath();
            if (!copyToOutput) {
                roots.add(new Root(source, null, true, List.of(Pattern.compile("(?s:.*)")), List.of()));
                continue;
            }
            Path target = output.resolve(p.getProperty(prefix + "target")).normalize();
            if (!target.startsWith(output) || output.startsWith(source) || source.startsWith(output))
                throw new IOException("Overlapping or external Maven resource destination");
            roots.add(new Root(source, target, Boolean.parseBoolean(p.getProperty(prefix + "filtered")),
                    patterns(p, prefix + "include."), patterns(p, prefix + "exclude.")));
        }
        var copy = new MavenResourceCopy(output, List.copyOf(roots), Boolean.parseBoolean(p.getProperty("overwrite")));
        if (copyToOutput) copy.rememberLaunchContents(served);
        return copy;
    }

    private static List<Pattern> patterns(Properties props, String prefix) {
        var result = new ArrayList<Pattern>();
        int n = Integer.parseInt(props.getProperty(prefix + "count"));
        for (int i = 0; i < n; i++) result.add(Pattern.compile(props.getProperty(prefix + i)));
        return List.copyOf(result);
    }

    List<Path> sources() { return roots.stream().map(Root::source).toList(); }
    Path output() { return output; }

    private void rememberLaunchContents(Set<Path> served) throws IOException {
        for (Root root : roots) {
            if (root.filtered || served.contains(root.source) || !Files.isDirectory(root.source)) continue;
            Files.walkFileTree(root.source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    Mapping mapping = mapping(file);
                    if (mapping != null && Files.isRegularFile(mapping.target, LinkOption.NOFOLLOW_LINKS)
                            && safe(output, mapping.target)) {
                        // On first save, compare the output against the source as it was at launch.
                        owned.put(mapping.target, new Output(key(mapping.target), digest(file)));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /** A destination has one producer even when the second root's file does not exist yet. */
    private Mapping mapping(Path source) {
        Mapping found = null;
        for (Root root : roots) {
            if (!source.startsWith(root.source)) continue;
            Path relative = root.source.relativize(source);
            if (!root.selects(relative)) continue;
            if (found != null || root.filtered) return null;
            found = new Mapping(root, root.target.resolve(relative));
        }
        if (found == null) return null;
        for (Root other : roots) {
            if (other != found.root && found.target.startsWith(other.target)
                    && other.selects(other.target.relativize(found.target))) return null;
        }
        return found;
    }

    synchronized Outcome saved(Path source, boolean deleted) {
        if (!source.isAbsolute() || !source.equals(source.normalize())) return Outcome.MANUAL;
        boolean selected = roots.stream().anyMatch(r -> source.startsWith(r.source)
                && r.selects(r.source.relativize(source)));
        if (!selected) return Outcome.EXCLUDED;
        if (deleted) return Outcome.DELETED;
        Mapping mapping = mapping(source);
        if (mapping == null) return Outcome.MANUAL;
        Path target = mapping.target;
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !safe(mapping.root.source, source)
                    || !safe(output, target)) return Outcome.MANUAL;
            // A targetPath alias in a different resource could denote this same destination.
            for (Root root : roots) if (!safe(output, root.target)) return Outcome.MANUAL;
            boolean exists = !Files.notExists(target, LinkOption.NOFOLLOW_LINKS);
            Output prior = owned.get(target);
            if (exists && (prior == null || !prior.matches(target))) return Outcome.MANUAL;
            if (exists && !overwrite && Files.getLastModifiedTime(target).toMillis()
                    >= Files.getLastModifiedTime(source).toMillis()) return Outcome.UNCHANGED;
            Files.createDirectories(target.getParent());
            if (!safe(output, target)) return Outcome.MANUAL;
            Path temp = Files.createTempFile(target.getParent(), ".reclazz-resource-", ".tmp");
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                Output next = new Output(key(temp), digest(temp));
                if (!safe(output, target) || !safe(mapping.root.source, source)
                        || !next.hash.equals(digest(source))) return Outcome.MANUAL;
                if (exists) {
                    if (!prior.matches(target)) return Outcome.MANUAL;
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // Publish complete bytes only if another writer has not already created the destination.
                    Files.createLink(target, temp);
                }
                owned.put(target, next);
                return Outcome.COPIED;
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            return Outcome.MANUAL;
        }
    }

    private static boolean safe(Path base, Path candidate) throws IOException {
        if (!candidate.equals(candidate.normalize()) || !candidate.startsWith(base)
                || !base.equals(base.toRealPath())) return false;
        Path current = base;
        for (Path name : base.relativize(candidate)) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) return false;
        }
        return true;
    }

    private static Object key(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static String digest(Path file) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            for (int n; (n = in.read(buffer)) != -1;) md.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
