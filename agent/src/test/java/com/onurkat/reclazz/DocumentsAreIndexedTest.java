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
 * The README's Documentation table is how a reader finds the rest. Every
 * document under docs/ is in it, every link in it resolves, and a document
 * added without a row, or removed with its row left behind, fails here.
 */
class DocumentsAreIndexedTest {

    @Test
    void everyDocumentIsListedAndEveryListedDocumentExists() throws IOException {
        Path repo = AgentSources.root().getParent().getParent().getParent().getParent();
        String readme = Files.readString(repo.resolve("README.md"));
        int start = readme.indexOf("## Documentation");
        assertTrue(start >= 0, "README.md has no Documentation section");
        String section = readme.substring(start, readme.indexOf("\n## ", start + 1));

        List<String> linked = new ArrayList<>();
        Matcher m = Pattern.compile("\\]\\((docs/[\\w./-]+\\.md|CHANGELOG\\.md)\\)").matcher(section);
        while (m.find()) linked.add(m.group(1));

        List<String> problems = new ArrayList<>();
        for (String link : linked) {
            if (!Files.isRegularFile(repo.resolve(link))) problems.add("linked but missing: " + link);
        }
        try (Stream<Path> docs = Files.list(repo.resolve("docs"))) {
            for (Path doc : (Iterable<Path>) docs.filter(p -> p.toString().endsWith(".md"))::iterator) {
                String rel = "docs/" + doc.getFileName();
                if (!linked.contains(rel)) problems.add("not in the README's table: " + rel);
            }
        }
        assertEquals(List.of(), problems);
    }
}
