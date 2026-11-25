/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/** Immutable data written by the Maven prepare-agent goal for one module. */
public record MavenCompilerSnapshot(String classpath, Charset encoding, List<Path> roots,
                                    List<String> flags, Path generated, boolean mapstruct) {
    public MavenCompilerSnapshot {
        roots = List.copyOf(roots);
        flags = List.copyOf(flags);
    }

    static MavenCompilerSnapshot read(String location) throws IOException {
        String file = new String(Base64.getUrlDecoder().decode(location), StandardCharsets.UTF_8);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(file))) {
            properties.load(reader);
        }
        if (!System.getProperty("java.specification.version").equals(properties.getProperty("jdk"))) {
            throw new IOException("Maven and the running application use different JDKs; use Maven to compile");
        }
        if (ModuleLayer.boot().findModule("jdk.compiler").isEmpty()) {
            throw new IOException("The running application has no jdk.compiler module");
        }
        String classpath = Objects.requireNonNull(properties.getProperty("classpath"));
        Charset encoding = Charset.forName(properties.getProperty("encoding"));
        List<Path> roots = Arrays.stream(properties.getProperty("roots").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
        List<String> flags = new ArrayList<>();
        for (int i = 0; properties.containsKey("option." + i); i++) {
            flags.add(properties.getProperty("option." + i));
        }
        String generated = properties.getProperty("generated");
        return new MavenCompilerSnapshot(classpath, encoding, roots, flags,
                generated == null ? null : Path.of(generated), Boolean.parseBoolean(properties.getProperty("mapstruct")));
    }
}
