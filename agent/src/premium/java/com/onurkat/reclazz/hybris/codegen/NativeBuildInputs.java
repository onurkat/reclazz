/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import com.onurkat.reclazz.platform.codegen.GeneratedPublication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

final class NativeBuildInputs {
    final Map<Path,String> inputs;
    final Map<Path,String> compilePath;

    private NativeBuildInputs(Map<Path,String> inputs, Map<Path,String> compilePath) {
        this.inputs = Map.copyOf(inputs);
        this.compilePath = Map.copyOf(compilePath);
    }

    static NativeBuildInputs capture(Path platform, Properties compiler, Properties extensions) throws IOException {
        Map<Path,String> files = new HashMap<>();
        Path config = Path.of(compiler.getProperty("HYBRIS_CONFIG_DIR"));
        inventory(config, p -> resource(p) || p.getFileName().toString().equals("localextensions.xml"), files);
        for (String name : List.of("build.xml", "project.properties", "bootstrap/bin/ybootstrap.jar",
                "bootstrap/bin/yant.jar", "resources/advanced.properties", "setantenv.sh", "setantenv.bat"))
            add(platform.resolve(name), files);
        inventory(platform.resolve("resources/ant"), p -> true, files);
        for (String name : extensions.stringPropertyNames()) {
            Path root = Path.of(extensions.getProperty(name)).toRealPath();
            for (String file : List.of("extensioninfo.xml", "buildcallbacks.xml", "project.properties")) add(root.resolve(file), files);
            for (String sources : List.of("src", "gensrc", "web/src", "backoffice/src")) {
                inventory(root.resolve(sources), NativeBuildInputs::source, files);
            }
            inventory(root.resolve("resources"), NativeBuildInputs::resource, files);
        }
        Map<Path,String> compile = new HashMap<>();
        for (Path entry : NativeCompilerPolicy.classpath(platform,compiler)) {
            // GeneratedBaseline checks this entire loose tree against the archive and hashes the jar.
            if (entry.equals(platform.resolve("bootstrap/modelclasses")) ||
                    entry.equals(platform.resolve("bootstrap/bin/models.jar"))) continue;
            if (Files.isDirectory(entry)) inventory(entry, p -> p.toString().endsWith(".class")
                    || p.getFileName().toString().equals("javax.annotation.processing.Processor"), compile);
            else add(entry, compile);
        }
        return new NativeBuildInputs(files, compile);
    }

    void verify(NativeBuildInputs current, Path edited) throws IOException {
        if (!compilePath.equals(current.compilePath)) throw new IOException("Native compile classpath changed");
        if (!inputs.keySet().equals(current.inputs.keySet())) throw new IOException("Build input was added or removed");
        Path actual = edited.toRealPath();
        if (!inputs.containsKey(actual)) throw new IOException("Edited file is outside native generator inputs");
        for (var entry : inputs.entrySet()) {
            if (!entry.getKey().equals(actual) && !entry.getValue().equals(current.inputs.get(entry.getKey())))
                throw new IOException("Another generator or ordinary extension input changed");
        }
    }

    private static boolean source(Path p) {
        String name=p.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".groovy") || name.endsWith(".kt");
    }

    private static boolean resource(Path p) {
        String name=p.getFileName().toString();
        return name.endsWith(".xml") || name.endsWith(".vm") || name.endsWith(".properties");
    }

    private static void add(Path p, Map<Path,String> output) throws IOException {
        if (Files.isSymbolicLink(p)) throw new IOException("Redirected build input");
        if (output.containsKey(p)) return;
        output.put(p, GeneratedPublication.digest(p));
    }

    private static void inventory(Path root, Predicate<Path> include, Map<Path,String> output) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path file : (Iterable<Path>) paths::iterator) {
                if (Files.isSymbolicLink(file)) throw new IOException("Redirected build input tree");
                if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) && include.test(file)) add(file, output);
            }
        }
    }

    Set<String> possibleConsumerNames(Set<String> names, Set<Path> generated) throws IOException {
        Set<String> found = new HashSet<>();
        if (names.isEmpty()) return found;
        for (Path candidate : inputs.keySet()) {
            if (!source(candidate) || generated.contains(candidate)) continue;
            String text = Files.readString(candidate);
            // Unicode escapes may spell an otherwise invisible Java identifier.
            if (text.contains("\\u")) {
                var decoded = new StringBuilder();
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) == '\\' && i + 1 < text.length() && text.charAt(i + 1) == 'u') {
                        int hex=i+2;
                        while (hex<text.length() && text.charAt(hex)=='u') hex++;
                        if (hex+4>text.length()) throw new IOException("Unknown consumer source escape");
                        try { decoded.append((char) Integer.parseInt(text.substring(hex,hex+4),16)); }
                        catch (NumberFormatException unknown) { throw new IOException("Unknown consumer source escape",unknown); }
                        i=hex+3;
                    } else decoded.append(text.charAt(i));
                }
                text=decoded.toString();
            }
            for (String name : names) if (text.contains(name)) found.add(name);
            if (!found.isEmpty()) return found;
        }
        return found;
    }
}
