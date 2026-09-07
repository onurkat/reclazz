/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code toLowerCase()} and {@code toUpperCase()} without a locale use the
 * JVM's default, and in a Turkish one an upper-case I lowers to a dotless
 * one: {@code "JetBrains".toLowerCase()} is {@code "jetbraıns"}, which
 * contains no {@code "jetbrains"}. Every comparison the agent makes on a
 * lowered string is a comparison against ASCII it wrote itself, so every
 * conversion names {@code Locale.ROOT}. Three did not, and one of them
 * decided whether a JetBrains Runtime was one.
 */
class CaseConversionsNameALocaleTest {

    @Test
    void everyCaseConversionInTheAgentNamesALocale() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(AgentSources.root())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains(".toLowerCase()") || line.contains(".toUpperCase()")) {
                        offenders.add(AgentSources.root().relativize(file) + ":" + (i + 1) + ": " + line.strip());
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "case conversions that depend on the machine's locale");
    }
}
