/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import com.onurkat.reclazz.AgentSources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The output counts things, and it has to count them in English.
 *
 * <p>Found by watching the plugin's own enabled state rather than by reading
 * code: one method added to one class produced {@code Structural reload:
 * app.Greeter (v1, +1 method(s))} in the tool window and {@code Reclazz: 1
 * reloads} in the status bar, at the same moment, for the same event. The
 * parenthesis is what a developer writes when the count is a variable, and it
 * is also the clearest sign in the output that nobody read it back.
 *
 * <p>The first two tests are the rule. The third is the guard: it reads this
 * agent's own sources and fails on a message template that reintroduces the
 * shape, because a sweep that nothing holds in place is a sweep with a
 * half-life.
 */
class CountsReadAsEnglishTest {

    @Test
    void oneIsSingularAndEverythingElseIsNot() {
        assertEquals("1 method", Plural.of(1, "method"));
        assertEquals("2 methods", Plural.of(2, "method"));
        assertEquals("0 methods", Plural.of(0, "method"),
                "zero takes the plural, which is why the count decides and not emptiness");
    }

    @Test
    void anIrregularNounBringsItsOwnPlural() {
        assertEquals("1 property", Plural.of(1, "property", "properties"));
        assertEquals("3 properties", Plural.of(3, "property", "properties"));
        assertEquals("was", Plural.word(1, "was", "were"));
        assertEquals("were", Plural.word(4, "was", "were"));
    }

    /**
     * The parenthesis, in a string literal, in this agent's own source. Comments
     * are exempt: prose about "method(s)" is describing the thing, not printing
     * it, and this file is exempt for the same reason.
     */
    @Test
    void noMessageStillSpellsAPluralWithAParenthesis() throws IOException {
        Path root = AgentSources.root();
        List<String> offenders = new ArrayList<>();

        for (Path file : AgentSources.javaFiles()) {
            if (file.getFileName().toString().equals("Plural.java")) continue;
            List<String> lines = AgentSources.lines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!looksLikeALiteralPlural(line)) continue;
                offenders.add(root.relativize(file) + ":" + (i + 1) + "  " + line.trim());
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "these lines print a count and then hedge the noun:\n"
                        + String.join("\n", offenders));
    }

    /**
     * A literal "(s)" that is inside a quoted string and not inside a comment.
     * Crude on purpose: it is a lint, and the cost of a false positive is one
     * rewritten sentence.
     */
    private static boolean looksLikeALiteralPlural(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("*") || trimmed.startsWith("//")) return false;
        int at = line.indexOf("(s)");
        if (at < 0) return false;
        return quotesBefore(line, at) % 2 == 1;
    }

    private static int quotesBefore(String line, int index) {
        int count = 0;
        for (int i = 0; i < index; i++) {
            if (line.charAt(i) == '"' && (i == 0 || line.charAt(i - 1) != '\\')) count++;
        }
        return count;
    }

}
