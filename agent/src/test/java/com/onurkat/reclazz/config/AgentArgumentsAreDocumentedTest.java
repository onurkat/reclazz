/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.config;

import com.onurkat.reclazz.AgentSources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The agent's arguments are a contract: a line written for one 1.x agent
 * starts the next. The table in docs/usage.md is where that contract is
 * read, so it has to list exactly what the agent accepts. Nine of nineteen
 * arguments were missing from it; a reader could not have known they
 * existed, let alone that they would stay.
 */
class AgentArgumentsAreDocumentedTest {

    @SuppressWarnings("unchecked")
    private static Set<String> knownKeys() throws Exception {
        Field field = AgentConfig.class.getDeclaredField("KNOWN_KEYS");
        field.setAccessible(true);
        return new TreeSet<>((Set<String>) field.get(null));
    }

    private static Set<String> documentedKeys() throws IOException {
        String doc = Files.readString(AgentSources.root().getParent().getParent().getParent().getParent()
                .resolve("docs/usage.md"));
        int start = doc.indexOf("### Agent Arguments");
        assertTrue(start >= 0, "docs/usage.md has no 'Agent Arguments' section");
        int end = doc.indexOf("\n### ", start + 1);
        String section = doc.substring(start, end < 0 ? doc.length() : end);
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("^\\| `([A-Za-z]+)` \\|", Pattern.MULTILINE).matcher(section);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    @Test
    void theTableAndTheAgentAcceptTheSameArguments() throws Exception {
        assertEquals(knownKeys(), documentedKeys(),
                "docs/usage.md's Agent Arguments table and AgentConfig.KNOWN_KEYS disagree; "
                        + "adding an argument means adding its row, and an argument is never removed within 1.x");
    }

    /**
     * Placed first, as the documentation says to: the line is split at a
     * comma only where a known argument follows, so an unknown one after
     * another would be read as part of that one's value.
     */
    @Test
    void anUnknownArgumentIsNamedAndNotFatal() {
        AgentConfig config = AgentConfig.parse("fromAFutureRelease=1,verbose=true");
        assertNotNull(config, "a newer line still starts an older agent");
        assertTrue(config.isVerbose());
        assertEquals(java.util.List.of("fromAFutureRelease"), config.getUnknownKeys());
    }
}
