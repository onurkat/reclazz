/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.agent.AgentConfig;
import com.onurkat.reclazz.ui.StatusReporter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * PlatformContext for standalone Spring Boot and generic Java applications.
 *
 * Auto-detects class output directories:
 * - Maven: target/classes
 * - Gradle: build/classes/java/main
 * - Explicit: watchDirs= agent argument
 *
 * ApplicationContext is obtained via ApplicationContextHolder (populated by
 * SpringContextInterceptTransformer at runtime).
 */
public class SpringBootContext implements PlatformContext {

    private final AgentConfig config;
    private final Map<String, List<Path>> classOutputDirs = new LinkedHashMap<>();
    private final Map<String, List<Path>> sourceDirs = new LinkedHashMap<>();
    private final Map<String, List<Path>> resourceDirs = new LinkedHashMap<>();
    private String classpath;

    public SpringBootContext(AgentConfig config) {
        this.config = config;
    }

    @Override
    public Platform getPlatformId() {
        return Platform.SPRING_BOOT;
    }

    @Override
    public void initialize() {
        // Use explicit watchDirs if provided
        List<Path> explicitDirs = config.getWatchDirs();
        if (explicitDirs != null && !explicitDirs.isEmpty()) {
            List<Path> validDirs = new ArrayList<>();
            for (Path dir : explicitDirs) {
                if (Files.isDirectory(dir)) {
                    validDirs.add(dir);
                    StatusReporter.info("Watch directory: " + dir);
                } else {
                    StatusReporter.warn("Watch directory not found: " + dir);
                }
            }
            if (!validDirs.isEmpty()) {
                classOutputDirs.put("app", validDirs);
            }
        } else {
            // Auto-detect from classpath
            autoDetectOutputDirs();
        }

        // Auto-detect source directories relative to output dirs
        autoDetectSourceDirs();

        // Auto-detect resource directories
        autoDetectResourceDirs();

        // Resolve classpath from system property
        classpath = System.getProperty("java.class.path");

        int totalDirs = classOutputDirs.values().stream().mapToInt(List::size).sum();
        StatusReporter.info("Watching " + totalDirs + " class output directories");
    }

    /**
     * Auto-detect class output directories from java.class.path.
     * Looks for Maven (target/classes) and Gradle (build/classes) patterns.
     */
    private void autoDetectOutputDirs() {
        String cp = System.getProperty("java.class.path");
        if (cp == null || cp.isEmpty()) return;

        List<Path> detected = new ArrayList<>();
        for (String entry : cp.split(File.pathSeparator)) {
            Path path = Paths.get(entry);
            if (!Files.isDirectory(path)) continue;

            String pathStr = path.toString().replace('\\', '/');

            // Maven: target/classes
            if (pathStr.endsWith("/target/classes") || pathStr.endsWith("/target/test-classes")) {
                detected.add(path);
                StatusReporter.info("Detected Maven output: " + path);
            }
            // Gradle: build/classes/java/main
            else if (pathStr.contains("/build/classes/")) {
                detected.add(path);
                StatusReporter.info("Detected Gradle output: " + path);
            }
        }

        // If no build tool dirs found, try CWD-relative defaults
        if (detected.isEmpty()) {
            Path cwd = Paths.get(System.getProperty("user.dir"));
            Path mavenDir = cwd.resolve("target").resolve("classes");
            Path gradleDir = cwd.resolve("build").resolve("classes").resolve("java").resolve("main");

            if (Files.isDirectory(mavenDir)) {
                detected.add(mavenDir);
                StatusReporter.info("Detected Maven output (CWD): " + mavenDir);
            }
            if (Files.isDirectory(gradleDir)) {
                detected.add(gradleDir);
                StatusReporter.info("Detected Gradle output (CWD): " + gradleDir);
            }
        }

        if (!detected.isEmpty()) {
            classOutputDirs.put("app", detected);
        } else {
            StatusReporter.warn("No class output directories detected. " +
                    "Use watchDirs= to specify directories explicitly.");
        }
    }

    private void autoDetectSourceDirs() {
        for (var entry : classOutputDirs.entrySet()) {
            String module = entry.getKey();
            List<Path> srcPaths = new ArrayList<>();

            for (Path outputDir : entry.getValue()) {
                String pathStr = outputDir.toString().replace('\\', '/');

                // Maven: target/classes -> src/main/java
                if (pathStr.endsWith("/target/classes")) {
                    Path projectRoot = outputDir.getParent().getParent();
                    Path srcDir = projectRoot.resolve("src").resolve("main").resolve("java");
                    if (Files.isDirectory(srcDir)) {
                        srcPaths.add(srcDir);
                    }
                }
                // Gradle: build/classes/java/main -> src/main/java
                else if (pathStr.contains("/build/classes/")) {
                    Path projectRoot = outputDir;
                    while (projectRoot != null && !projectRoot.getFileName().toString().equals("build")) {
                        projectRoot = projectRoot.getParent();
                    }
                    if (projectRoot != null) {
                        projectRoot = projectRoot.getParent();
                        Path srcDir = projectRoot.resolve("src").resolve("main").resolve("java");
                        if (Files.isDirectory(srcDir)) {
                            srcPaths.add(srcDir);
                        }
                    }
                }
            }

            if (!srcPaths.isEmpty()) {
                sourceDirs.put(module, srcPaths);
            }
        }
    }

    private void autoDetectResourceDirs() {
        for (var entry : classOutputDirs.entrySet()) {
            String module = entry.getKey();
            List<Path> resPaths = new ArrayList<>();

            for (Path outputDir : entry.getValue()) {
                String pathStr = outputDir.toString().replace('\\', '/');

                // Maven: target/classes -> src/main/resources
                if (pathStr.endsWith("/target/classes")) {
                    Path projectRoot = outputDir.getParent().getParent();
                    Path resDir = projectRoot.resolve("src").resolve("main").resolve("resources");
                    if (Files.isDirectory(resDir)) {
                        resPaths.add(resDir);
                    }
                }
                // Gradle: build/classes/java/main -> src/main/resources
                else if (pathStr.contains("/build/classes/")) {
                    Path projectRoot = outputDir;
                    while (projectRoot != null && !projectRoot.getFileName().toString().equals("build")) {
                        projectRoot = projectRoot.getParent();
                    }
                    if (projectRoot != null) {
                        projectRoot = projectRoot.getParent();
                        Path resDir = projectRoot.resolve("src").resolve("main").resolve("resources");
                        if (Files.isDirectory(resDir)) {
                            resPaths.add(resDir);
                        }
                    }
                }
            }

            if (!resPaths.isEmpty()) {
                resourceDirs.put(module, resPaths);
            }
        }
    }

    @Override
    public Map<String, List<Path>> getClassOutputDirs() {
        return classOutputDirs;
    }

    @Override
    public Map<String, List<Path>> getSourceDirs() {
        return sourceDirs;
    }

    @Override
    public Map<String, List<Path>> getResourceDirs() {
        return resourceDirs;
    }

    @Override
    public String resolveClasspath() {
        return classpath;
    }

    @Override
    public String resolveClassName(Path classFile) {
        for (List<Path> dirs : classOutputDirs.values()) {
            for (Path dir : dirs) {
                if (classFile.startsWith(dir)) {
                    Path relative = dir.relativize(classFile);
                    return relative.toString()
                            .replace(".class", "")
                            .replace(File.separatorChar, '.')
                            .replace('/', '.');
                }
            }
        }
        return null;
    }

    @Override
    public Path resolveOutputDir(Path classFile) {
        for (List<Path> dirs : classOutputDirs.values()) {
            for (Path dir : dirs) {
                if (classFile.startsWith(dir)) {
                    return dir;
                }
            }
        }
        return null;
    }

    @Override
    public Object getApplicationContext() {
        return ApplicationContextHolder.getApplicationContext();
    }

    @Override
    public java.util.List<Object> getAllApplicationContexts() {
        return ApplicationContextHolder.getAllContexts();
    }
}
