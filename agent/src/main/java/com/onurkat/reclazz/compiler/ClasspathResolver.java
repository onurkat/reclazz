/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.hybris.ExtensionInfo;
import com.onurkat.reclazz.hybris.HybrisContext;
import com.onurkat.reclazz.ui.StatusReporter;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Resolves the complete classpath for SAP Commerce compilation.
 *
 * Collects JARs and class directories from:
 *   - platform/lib/
 *   - platform/bootstrap/bin/
 *   - Each extension's lib/ directory
 *   - Each extension's classes/ directory
 *   - Each extension's bin/ directory
 */
public class ClasspathResolver {

    private final HybrisContext context;

    public ClasspathResolver(HybrisContext context) {
        this.context = context;
    }

    /**
     * Resolve the complete classpath string for compilation.
     */
    public String resolve() {
        Set<String> entries = new LinkedHashSet<>();

        Path platformHome = context.getPlatformHome();

        // Platform core libraries
        collectJars(platformHome.resolve("lib"), entries);

        // Platform bootstrap (model classes)
        Path bootstrapBin = platformHome.resolve("bootstrap").resolve("bin");
        if (Files.isDirectory(bootstrapBin)) {
            entries.add(bootstrapBin.toString());
            collectJars(bootstrapBin, entries);
        }

        // Platform ext libraries. ext/ itself contains no jars — each
        // platform extension keeps them in ext/<name>/lib (Spring lives in
        // ext/core/lib). These extensions are auto-required and absent from
        // localextensions.xml, so they never appear in the extensions map
        // below and must be walked here explicitly.
        Path extRoot = platformHome.resolve("ext");
        if (Files.isDirectory(extRoot)) {
            try (Stream<Path> subs = Files.list(extRoot)) {
                subs.filter(Files::isDirectory).sorted().forEach(sub -> {
                    collectJars(sub.resolve("lib"), entries);
                    Path classesDir = sub.resolve("classes");
                    if (Files.isDirectory(classesDir)) {
                        entries.add(classesDir.toString());
                    }
                    Path binDir = sub.resolve("bin");
                    if (Files.isDirectory(binDir)) {
                        entries.add(binDir.toString());
                        // Platform extensions ship compiled code as jars in
                        // bin/ (e.g. ext/core/bin/coreserver.jar holds the
                        // whole servicelayer) — a bare directory entry does
                        // not reach classes inside those jars.
                        collectJars(binDir, entries);
                    }
                    collectJars(sub.resolve("web").resolve("webroot")
                            .resolve("WEB-INF").resolve("lib"), entries);
                });
            } catch (IOException e) {
                StatusReporter.warn("Could not scan platform ext directory: " + extRoot);
            }
        }

        // Tomcat container libs (jakarta.servlet API for web-module compiles)
        collectJars(platformHome.resolve("tomcat").resolve("lib"), entries);

        // All extensions' lib/ and classes/ directories
        for (ExtensionInfo ext : context.getExtensions().values()) {
            Path extPath = ext.getPath();

            // Extension lib/
            collectJars(extPath.resolve("lib"), entries);

            // Extension classes/
            Path classesDir = extPath.resolve("classes");
            if (Files.isDirectory(classesDir)) {
                entries.add(classesDir.toString());
            }

            // Extension bin/ (Eclipse output, or <name>server.jar for
            // module extensions)
            Path binDir = extPath.resolve("bin");
            if (Files.isDirectory(binDir)) {
                entries.add(binDir.toString());
                collectJars(binDir, entries);
            }

            // Web module
            Path webLib = extPath.resolve("web").resolve("webroot").resolve("WEB-INF").resolve("lib");
            collectJars(webLib, entries);

            Path webClasses = extPath.resolve("web").resolve("webroot").resolve("WEB-INF").resolve("classes");
            if (Files.isDirectory(webClasses)) {
                entries.add(webClasses.toString());
            }
        }

        String classpath = String.join(File.pathSeparator, entries);
        StatusReporter.info("Classpath resolved: " + entries.size() + " entries");

        return classpath;
    }

    private void collectJars(Path directory, Set<String> entries) {
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .sorted()
                    .forEach(jar -> entries.add(jar.toString()));
        } catch (IOException e) {
            StatusReporter.warn("Could not scan directory: " + directory);
        }
    }
}
