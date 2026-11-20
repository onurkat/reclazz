/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.nio.file.*;
import java.util.Collection;
import java.util.List;

/** Proven bootstrap outputs must not shadow platform models in an extension. */
public final class BootstrapGeneratedCopies {
    private BootstrapGeneratedCopies() { }
    private static final List<String> OWN_ROOTS = List.of("src", "gensrc", "web/src", "web/gensrc", "backoffice/src", "backoffice/gensrc");

    public static boolean isPlatformGenerated(byte[] bytes, Path platform, Path extension) {
        try {
            ClassNode node = metadata(bytes);
            String source = node.sourceFile;
            if (source == null || !source.endsWith(".java") || source.contains("/") || source.contains("\\")
                    || node.name == null || node.name.contains("..")) return false;
            Path generated = platform.resolve("bootstrap/gensrc").toRealPath();
            int slash = node.name.lastIndexOf('/');
            Path file = generated.resolve((slash < 0 ? "" : node.name.substring(0, slash + 1)) + source);
            if (!Files.isRegularFile(file) || !Files.isReadable(file) || !file.toRealPath().startsWith(generated)) return false;
            // A SourceFile basename in any own layout is ambiguous, including
            // secondary top-level classes and nonstandard package directories.
            for (String relative : OWN_ROOTS) {
                Path root = extension.resolve(relative);
                if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false;
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root) || !Files.isReadable(root)) return false;
                try (var walk = Files.walk(root)) {
                    if (walk.anyMatch(p -> (Files.isSymbolicLink(p) && Files.isDirectory(p))
                            || p.getFileName().toString().equals(source))) return false;
                }
            }
            return true;
        } catch (Exception | LinkageError unknown) {
            return false;
        }
    }

    public static int remove(Path platform, Collection<ExtensionInfo> extensions) {
        int removed = 0;
        for (ExtensionInfo extension : extensions) {
            Path classes = extension.getPath().resolve("classes");
            try {
                if (Files.isSymbolicLink(classes) || !Files.isDirectory(classes)) continue;
                try (var walk = Files.walk(classes)) {
                    for (Path file : walk.filter(p -> p.toString().endsWith(".class") && !Files.isSymbolicLink(p)
                            && Files.isRegularFile(p)).toList()) {
                        try {
                            byte[] before = Files.readAllBytes(file);
                            ClassNode node = metadata(before);
                            if (!classes.relativize(file).equals(Path.of(node.name + ".class"))
                                    || !isPlatformGenerated(before, platform, extension.getPath())) continue;
                            // Preserve replacements observed while proving ownership.
                            if (!java.util.Arrays.equals(before, Files.readAllBytes(file))) continue;
                            Files.delete(file);
                            removed++;
                            StatusReporter.detail("Removed bootstrap-generated extension copy: " + file);
                        } catch (Exception | LinkageError unknown) { /* Ownership must be proven per file. */ }
                    }
                }
            } catch (Exception unknown) { /* An unreadable tree grants no cleanup authority. */ }
        }
        if (removed > 0) StatusReporter.info("Removed " + removed + " bootstrap-generated class copies from extension output");
        return removed;
    }

    private static ClassNode metadata(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return node;
    }
}
