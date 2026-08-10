/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Abstraction layer for different runtime platforms.
 * Implementations provide platform-specific class output directories,
 * source directories, classpath resolution, and Spring ApplicationContext access.
 */
public interface PlatformContext {

    enum Platform {
        HYBRIS,
        SPRING_BOOT,
        GENERIC
    }

    /**
     * Returns the platform identifier.
     */
    Platform getPlatformId();

    /**
     * Initialize the platform context (discover modules, scan directories, etc.).
     */
    void initialize() throws Exception;

    /**
     * Returns class output directories to watch for .class file changes.
     * Each entry maps a module name to its output directory path.
     */
    Map<String, List<Path>> getClassOutputDirs();

    /**
     * Returns source directories to watch for .java file changes (autoCompile mode).
     * Each entry maps a module name to its source directory path.
     */
    Map<String, List<Path>> getSourceDirs();

    /**
     * Returns resource directories to watch for config changes.
     * Each entry maps a module name to its resource directory path.
     */
    Map<String, List<Path>> getResourceDirs();

    /**
     * Resolve the full classpath for incremental compilation.
     */
    String resolveClasspath();

    /**
     * Resolve a fully qualified class name from a .class file path.
     *
     * @param classFile the path to the .class file
     * @return the fully qualified class name, or null if it cannot be resolved
     */
    String resolveClassName(Path classFile);

    /**
     * Resolve the output directory root for a given .class file path.
     *
     * @param classFile the path to the .class file
     * @return the output directory root, or null if not found
     */
    Path resolveOutputDir(Path classFile);

    /**
     * Get the Spring ApplicationContext via reflection.
     * Returns null if the context is not yet available.
     */
    Object getApplicationContext();

    /**
     * Get ALL live Spring ApplicationContexts (e.g. the Hybris global
     * context plus every web application context). Spring reloaders must
     * iterate these — a reloaded controller lives in a web context, not
     * the global one. Never returns null; empty list when no context is
     * available yet.
     */
    default List<Object> getAllApplicationContexts() {
        Object ctx = getApplicationContext();
        return ctx == null ? List.of() : List.of(ctx);
    }

    /**
     * Returns the module name for a given file path, or "default" if not applicable.
     */
    default String resolveModuleName(Path filePath) {
        return "default";
    }

    /**
     * Whether this module should be watched (e.g., skip platform extensions in Hybris).
     */
    default boolean shouldWatch(String moduleName) {
        return true;
    }
}
