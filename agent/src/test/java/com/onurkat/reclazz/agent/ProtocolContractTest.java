/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.AgentSources;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/protocol.md is what a client other than the plugin is written
 * against, so it has to say what the code does: the fields the JSON lines
 * carry, the levels they use, and the commands the socket answers.
 */
class ProtocolContractTest {

    private static String doc() throws IOException {
        Path root = AgentSources.root().getParent().getParent().getParent().getParent();
        return Files.readString(root.resolve("docs/protocol.md"));
    }

    private static Set<String> tableColumn(String doc, String heading) {
        // First-column entries, in backticks, of the table under the heading.
        int start = doc.indexOf(heading);
        assertTrue(start >= 0, "docs/protocol.md has no section " + heading);
        int end = doc.indexOf("\n## ", start + 1);
        String section = doc.substring(start, end < 0 ? doc.length() : end);
        Set<String> names = new TreeSet<>();
        Matcher m = Pattern.compile("^\\| `([^`]+)` \\|", Pattern.MULTILINE).matcher(section);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    @Test
    void theFieldsTheServerEmitsAreTheDocumentedOnes() throws IOException {
        Set<String> documented = tableColumn(doc(), "## The stream");
        Set<String> emitted = new TreeSet<>();
        String server = Files.readString(AgentSources.root().resolve("com/onurkat/reclazz/agent/StatusServer.java"));
        Matcher m = Pattern.compile("\\\\\"([a-z]+)\\\\\":").matcher(server);
        while (m.find()) emitted.add(m.group(1));
        assertEquals(documented, emitted, "docs/protocol.md and StatusServer disagree about the JSON fields");
    }

    @Test
    void everyLevelTheReporterUsesIsDocumented() throws IOException {
        String doc = doc();
        List<String> seen = new CopyOnWriteArrayList<>();
        StatusReporter.StatusListener listener = (level, message) -> seen.add(level);
        StatusReporter.addListener(listener);
        try {
            StatusReporter.info("i");
            StatusReporter.success("s");
            StatusReporter.warn("w");
            StatusReporter.error("e");
            StatusReporter.reload("demo.A", 1);
            StatusReporter.structuralReload("demo.B", 1, "v1");
            StatusReporter.compile("A.java", 1);
        } finally {
            StatusReporter.removeListener(listener);
        }
        for (String level : new TreeSet<>(seen)) {
            assertTrue(doc.contains("`" + level + "`"), level + " is emitted and not documented");
        }
        for (String level : List.of("CONNECTED", "HEARTBEAT")) {
            assertTrue(doc.contains("`" + level + "`"), level + " is emitted by the server and not documented");
        }
    }

    @Test
    void theCommandsAreTheDocumentedOnes() throws IOException {
        Set<String> documented = new TreeSet<>();
        for (String entry : tableColumn(doc(), "## Commands")) documented.add(entry.split(" ")[0]);
        Set<String> served = new TreeSet<>();
        String server = Files.readString(AgentSources.root().resolve("com/onurkat/reclazz/agent/StatusServer.java"));
        Matcher m = Pattern.compile("private static final String ([A-Z]+) = \"([A-Z]+)\";").matcher(server);
        while (m.find()) served.add(m.group(2));
        assertEquals(documented, served, "docs/protocol.md and StatusServer disagree about the commands");
    }
}
