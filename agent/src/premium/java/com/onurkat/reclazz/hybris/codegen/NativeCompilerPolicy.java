/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.LinkOption;

final class NativeCompilerPolicy {
    private static final List<String> OPTIONS = List.of("build.compiler", "build.debug", "build.nowarn",
            "build.deprecation", "build.verbose", "build.source", "build.target", "build.encoding",
            "build.parameter-metadata", "standalone.jdkmodulesexports");

    private NativeCompilerPolicy() { }

    static Set<Path> classpath(Path platform, Properties settings) throws IOException {
        var defaults = new Properties();
        try (var in = Files.newInputStream(platform.resolve("resources/advanced.properties"))) { defaults.load(in); }
        for (String key : OPTIONS) {
            if (defaults.getProperty(key) == null || !Objects.equals(defaults.getProperty(key), settings.getProperty(key)))
                throw new IOException("Nondefault native compiler setting: " + key);
        }
        if (!"modern".equals(settings.getProperty("build.compiler"))) throw new IOException("Unsupported native compiler");

        var entries = new LinkedHashSet<Path>();
        String classpath = settings.getProperty("reclazz.compiler.entries");
        if (classpath == null) throw new IOException("Missing native compiler classpath");
        if (!classpath.isEmpty()) {
            for (String entry : classpath.split("\n", -1)) {
                if (entry.isBlank() || entry.contains("*") || entry.contains("${"))
                    throw new IOException("Unresolved native compiler classpath");
                Path resolved = Path.of(entry);
                if (!resolved.isAbsolute()) throw new IOException("Relative native compiler classpath entry");
                entries.add(resolved.normalize());
            }
        }
        entries.add(platform.resolve("bootstrap/modelclasses"));
        entries.add(platform.resolve("bootstrap/bin/models.jar"));
        try (var jars = Files.list(Path.of(settings.getProperty("bundled.tomcat.home")).resolve("lib"))) {
            entries.addAll(jars.filter(p -> p.toString().endsWith(".jar")).toList());
        }
        entries.add(Path.of(settings.getProperty("HYBRIS_TEMP_DIR")).resolve("log4j"));
        return Set.copyOf(entries);
    }

    static Map<Path, Path> expectedOutputs(Path classes, List<Path> sources) throws IOException {
        Set<Path> expected = new LinkedHashSet<>();
        for (Path source : sources) {
            String leaf = source.getFileName().toString();
            expected.add(source.resolveSibling(leaf.substring(0, leaf.length() - ".java".length()) + ".class"));
        }
        Map<Path, Path> outputs = new LinkedHashMap<>();
        try (var walk = Files.walk(classes)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                Path name = classes.relativize(file);
                if (name.startsWith("META-INF")) throw new IOException("Processor emitted additional generated metadata");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !expected.remove(name))
                    throw new IOException("Native compiler emitted an unexpected output");
                outputs.put(name, file);
            }
        }
        if (!expected.isEmpty()) throw new IOException("Native compiler omitted a generated class");
        return Map.copyOf(outputs);
    }
}
