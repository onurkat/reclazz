/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import com.onurkat.reclazz.AgentSources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No failure reaches the developer as its raw {@code getMessage()}.
 *
 * <p>That message is a sentence only sometimes. It is null for a plain
 * {@code IllegalStateException}, null for a closed WatchService, null for a
 * reflective call that threw with the real cause inside it, and for the file
 * exceptions it is the path with no reason attached. Fifty-seven call sites
 * appended it directly, which is where {@code FileWatcher error: null} and
 * {@code Failed to scan new directory /app/classes: /app/classes} came from.
 *
 * <p>{@link Failures#describe} is the one way to say it, and this is what
 * keeps the next one from going back to the short way.
 */
class RawMessagesAreNotReportedTest {

    private static final Pattern REPORT =
            Pattern.compile("StatusReporter\\s*\\.\\s*(warn|error|info|success)\\s*\\(");

    private static final Pattern RAW_MESSAGE =
            Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*getMessage\\s*\\(\\s*\\)");

    @Test
    void aFailureIsAlwaysDescribedRatherThanQuoted() throws IOException {
        List<String> raw = new ArrayList<>();
        for (Path file : AgentSources.javaFiles()) {
            if (file.getFileName().toString().equals("Failures.java")) continue;
            raw.addAll(scan(file));
        }

        assertEquals(List.of(), raw,
                "these hand the developer whatever getMessage() happened to return, which is "
                        + "null often enough to matter; wrap them in Failures.describe");
    }

    private static List<String> scan(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<String> found = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            if (!REPORT.matcher(lines.get(i)).find()) continue;

            StringBuilder statement = new StringBuilder();
            int end = i;
            while (end < lines.size() && end < i + 30) {
                statement.append(lines.get(end)).append(' ');
                if (lines.get(end).stripTrailing().endsWith(");")) break;
                end++;
            }
            if (RAW_MESSAGE.matcher(statement.toString()).find()) {
                found.add(file.getFileName() + ":" + (i + 1));
            }
            i = end;
        }
        return found;
    }

}
