/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which classes are watched for structural reload transformation.
 * Only custom extension classes are transformed — platform/library code is untouched.
 */
public class TransformContext {

    // Set of internal class names (e.g., "com/example/MyService") that should be transformed
    private final Set<String> watchedClassNames = ConcurrentHashMap.newKeySet();

    // Set of class output directories to scan for watchable classes
    private final Set<Path> watchedClassDirs = ConcurrentHashMap.newKeySet();

    // Metadata for each transformed class (populated after transform)
    private final ConcurrentHashMap<String, ClassMetadata> classMetadata = new ConcurrentHashMap<>();

    /**
     * Populate watched class names from a PlatformContext.
     * Scans all class output directories for .class files.
     */
    public void populateFromPlatformContext(PlatformContext platformContext) {
        for (var entry : platformContext.getClassOutputDirs().entrySet()) {
            for (Path dir : entry.getValue()) {
                if (Files.isDirectory(dir)) {
                    watchedClassDirs.add(dir);
                    scanClassDirectory(dir);
                }
            }
        }
    }

    private void scanClassDirectory(Path classesDir) {
        try {
            Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.toString();
                    if (fileName.endsWith(".class")) {
                        Path relative = classesDir.relativize(file);
                        String internalName = relative.toString()
                                .replace(".class", "")
                                .replace(java.io.File.separatorChar, '/');
                        watchedClassNames.add(internalName);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            StatusReporter.warn("Failed to scan class directory: " + classesDir + ": " + e.getMessage());
        }
    }

    /**
     * Check if a class (by internal name) should be transformed.
     * O(1) HashSet lookup.
     */
    public boolean isWatched(String internalName) {
        return watchedClassNames.contains(internalName);
    }

    /**
     * Register a class as watched at runtime (e.g., when a new class is first loaded).
     */
    public void addWatched(String internalName) {
        watchedClassNames.add(internalName);
    }

    /**
     * Check if a .class file path falls under one of the watched class directories.
     * Used to dynamically add new classes to the watched set.
     */
    public boolean isInWatchedDir(Path classFile) {
        for (Path dir : watchedClassDirs) {
            if (classFile.startsWith(dir)) return true;
        }
        return false;
    }

    public int getWatchedClassCount() {
        return watchedClassNames.size();
    }

    public ClassMetadata getMetadata(String internalName) {
        return classMetadata.get(internalName);
    }

    public void putMetadata(String internalName, ClassMetadata metadata) {
        classMetadata.put(internalName, metadata);
    }

    /**
     * Metadata about a transformed class — tracks original method/field signatures
     * so we can detect structural changes on reload.
     */
    public static class ClassMetadata {
        private final List<MethodSig> methods;
        private final List<FieldSig> fields;
        private final int bytecodeHash;
        private final String superName;
        private final java.util.Set<String> annotations;
        private final boolean annotationsKnown;

        /** For callers with no annotation information; the diff stays silent. */
        public ClassMetadata(List<MethodSig> methods, List<FieldSig> fields,
                             int bytecodeHash, String superName) {
            this.methods = List.copyOf(methods);
            this.fields = List.copyOf(fields);
            this.bytecodeHash = bytecodeHash;
            this.superName = superName;
            this.annotations = java.util.Set.of();
            this.annotationsKnown = false;
        }

        public ClassMetadata(List<MethodSig> methods, List<FieldSig> fields,
                             int bytecodeHash, String superName,
                             java.util.Set<String> annotations) {
            this.methods = List.copyOf(methods);
            this.fields = List.copyOf(fields);
            this.bytecodeHash = bytecodeHash;
            this.superName = superName;
            this.annotations = java.util.Set.copyOf(annotations);
            this.annotationsKnown = true;
        }

        public List<MethodSig> getMethods() { return methods; }
        public List<FieldSig> getFields() { return fields; }
        public int getBytecodeHash() { return bytecodeHash; }
        public String getSuperName() { return superName; }

        /**
         * Annotation signatures as of this version of the class, from
         * {@link AnnotationSignatures}. Empty when the class was recorded
         * before annotations were tracked, which the diff treats as
         * "nothing known" rather than "no annotations".
         */
        public java.util.Set<String> getAnnotations() { return annotations; }

        /**
         * Whether the annotation set was recorded at all. An empty set is a
         * real answer, "this class has no annotations", and a class that then
         * gains one has changed; that is different from never having looked,
         * which is what a class recorded by an older build looks like.
         */
        public boolean isAnnotationsKnown() { return annotationsKnown; }
    }

    public record MethodSig(String name, String descriptor, int access) {}
    public record FieldSig(String name, String descriptor, int access) {}
}
