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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compact README points to the canonical source index. Every
 * document under docs/ is indexed, every source link resolves, and a document
 * added without a row, or removed with its row left behind, fails here.
 */
class DocumentsAreIndexedTest {

    @Test
    void everyDocumentIsListedAndEveryListedDocumentExists() throws IOException {
        Path repo = AgentSources.root().getParent().getParent().getParent().getParent();
        String readme = Files.readString(repo.resolve("README.md"));
        assertTrue(readme.contains("https://reclazz.com/docs/"), "README must link to public documentation");
        assertTrue(readme.contains("](docs/index.md)"), "README must link to the canonical source index");
        String section = Files.readString(repo.resolve("docs/index.md"));

        List<String> linked = new ArrayList<>();
        Matcher m = Pattern.compile("\\]\\(([\\w./-]+\\.md)\\)").matcher(section);
        while (m.find()) linked.add(m.group(1));

        List<String> problems = new ArrayList<>();
        for (String link : linked) {
            if (!Files.isRegularFile(repo.resolve("docs").resolve(link))) problems.add("linked but missing: " + link);
        }
        try (Stream<Path> docs = Files.list(repo.resolve("docs"))) {
            for (Path doc : (Iterable<Path>) docs.filter(p -> p.toString().endsWith(".md"))::iterator) {
                String rel = doc.getFileName().toString();
                if (rel.equals("index.md")) continue;
                if (!linked.contains(rel)) problems.add("not in the source index: " + rel);
            }
        }
        assertEquals(List.of(), problems);
    }
}
