/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class StagedModelsJar {
    private StagedModelsJar() { }

    /** Build beside the staged loose classes. Publish neither artifact here. */
    static void build(Path original, Path staged, Map<Path, Path> compiled) throws IOException {
        Map<String, Path> replacements = new LinkedHashMap<>();
        for (var file : compiled.entrySet()) {
            Path relative = file.getKey();
            if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.startsWith("..")
                    || !relative.toString().endsWith(".class")) throw new IOException("Invalid generated class name");
            String name = relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
            replacements.put(name, file.getValue());
        }

        try (var source = new ZipFile(original.toFile());
             var out = new ZipOutputStream(Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW))) {
            var entries = source.entries();
            var seen = new HashSet<String>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String upper = name.toUpperCase(Locale.ROOT);
                if (!seen.add(name) || upper.startsWith("META-INF/VERSIONS/")
                        || upper.startsWith("META-INF/") && (upper.endsWith(".SF")
                        || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")))
                    throw new IOException("Unsupported models.jar layout");
                Path replacement = replacements.remove(name);
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                copy.setComment(entry.getComment());
                out.putNextEntry(copy);
                if (replacement == null) {
                    try (var in = source.getInputStream(entry)) { in.transferTo(out); }
                } else {
                    Files.copy(replacement, out);
                }
                out.closeEntry();
            }
            for (var file : replacements.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                Files.copy(file.getValue(), out);
                out.closeEntry();
            }
        }
    }
}
