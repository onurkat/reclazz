/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import com.onurkat.reclazz.AgentSources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The names that hold the engine together, spelled in one place.
 *
 * <p>A method whose body moved out is renamed to
 * {@code __reclazz$v0$<name>$<descHash>}, and a call site finds its
 * implementation by {@code <name>:<descHash>} or {@code static:<name>:<descHash>}.
 * The transformer writes them, the bootstrap classes read them back at
 * dispatch time from a different classloader, and nothing in between checks
 * that the two agree. When they do not, the reload lands and the call goes
 * nowhere: a NoSuchMethodError from inside the developer's own code.
 *
 * <p>They were spelled out by hand in five places for the renamed method and
 * six for the site key, across three packages, with the prefix itself a string
 * literal in nineteen files. Nothing about that arrangement is wrong until
 * someone edits one of them.
 *
 * <p>The prefix has already cost a release: a member injected without it was
 * visible to every framework that walks declared members, and SAP Commerce's
 * OCC layer fed it to JAXB and turned every response into an empty 400.
 */
class OneNamingAuthorityTest {

    /** A floor, so a walk that finds nothing cannot report a clean result. */
    private static final int AT_LEAST = 80;

    @Test
    void onlyInjectedNamesSpellsThePrefix() throws IOException {
        List<String> offenders = new ArrayList<>();
        int examined = scan(offenders, line -> line.contains("\"__reclazz$"));

        assertTrue(examined >= AT_LEAST,
                "only " + examined + " sources were read; the scan found nothing to check");
        assertTrue(offenders.isEmpty(),
                () -> "the injected-member prefix belongs to InjectedNames alone. A member "
                        + "written without it is visible to every framework that walks "
                        + "declared members:\n" + String.join("\n", offenders));
    }

    /**
     * The site key is the other half, and the easier one to rebuild by hand
     * because it looks like ordinary string concatenation rather than a name.
     */
    @Test
    void onlyInjectedNamesBuildsASiteKey() throws IOException {
        List<String> offenders = new ArrayList<>();
        int examined = scan(offenders, line -> line.contains("\"static:\"")
                || line.contains("+ \":\" + descHash"));

        assertTrue(examined >= AT_LEAST, "only " + examined + " sources were read");
        assertTrue(offenders.isEmpty(),
                () -> "a call-site key assembled outside InjectedNames:\n"
                        + String.join("\n", offenders));
    }

    /** And the scheme itself round-trips, which is what both halves rely on. */
    @Test
    void theSchemeIsWhatTheTransformerAndTheDispatcherBothExpect() {
        assertEquals("__reclazz$v0$greet$abc123", InjectedNames.renamed("greet", "abc123"));
        assertEquals("greet:abc123", InjectedNames.siteKey("greet", "abc123"));
        assertEquals("static:greet:abc123", InjectedNames.staticSiteKey("greet", "abc123"));
        assertEquals("static:greet:abc123", InjectedNames.siteKey("greet", "abc123", true));
        assertEquals("greet:abc123", InjectedNames.siteKey("greet", "abc123", false));

        assertTrue(InjectedNames.isInjected(InjectedNames.EXT_FIELD));
        assertTrue(InjectedNames.isInjected(InjectedNames.LOOKUP_FIELD));
        assertTrue(InjectedNames.isInjected(InjectedNames.INIT_METHOD));
        assertTrue(InjectedNames.isInjected(InjectedNames.renamed("greet", "abc123")),
                "a renamed body is an injected member too, and reflection must not see it");
        assertFalse(InjectedNames.isInjected("greet"));
    }

    /** Comments describe the names; only code is held to spelling them once. */
    private static int scan(List<String> offenders, java.util.function.Predicate<String> hits)
            throws IOException {
        Path root = AgentSources.root();
        int examined = 0;
        for (Path file : AgentSources.javaFiles()) {
            if (file.getFileName().toString().equals("InjectedNames.java")) continue;
            examined++;
            List<String> lines = AgentSources.lines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//")) continue;
                if (hits.test(line)) {
                    offenders.add(root.relativize(file) + ":" + (i + 1) + "  " + trimmed);
                }
            }
        }
        return examined;
    }

}
