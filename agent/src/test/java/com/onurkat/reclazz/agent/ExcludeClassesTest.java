/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Naming classes the transform should leave alone.
 *
 * <p>The way out when instrumentation itself is the problem, which is a thing
 * that happens: a class the transform cannot handle, a framework whose own
 * bytecode tricks do not survive being rewritten, a bug here. Until this
 * existed the only escape was detaching the agent, and a developer who hit one
 * class did exactly that, losing it for every other class in the process.
 *
 * <p>{@code excludePatterns} was not the answer and could not have been: it
 * excludes files from being watched, and instrumentation is not watching. It
 * happens at load time whether the file is ever edited or not.
 */
class ExcludeClassesTest {

    private static AgentConfig with(String patterns) {
        return AgentConfig.parse("excludeClasses=" + patterns);
    }

    @Test
    void nothingIsExcludedByDefault() {
        AgentConfig config = AgentConfig.parse(null);

        assertFalse(config.isClassExcluded("com.acme.Order"));
        assertTrue(config.getExcludeClasses().isEmpty());
    }

    @Test
    void aPackageIsExcludedByPattern() {
        AgentConfig config = with("com.generac.b2b.core.jalo.*");

        assertTrue(config.isClassExcluded("com.generac.b2b.core.jalo.Badge"));
        assertTrue(config.isClassExcluded("com.generac.b2b.core.jalo.GeneratedBadge"));
        assertFalse(config.isClassExcluded("com.generac.b2b.core.service.BadgeService"));
    }

    /** The transformer has internal names; a developer writes binary ones. */
    @Test
    void anInternalNameMatchesThePatternADeveloperWrote() {
        AgentConfig config = with("com.acme.jalo.*");

        assertTrue(config.isClassExcluded("com/acme/jalo/Order"));
        assertTrue(config.isClassExcluded("com.acme.jalo.Order"));
    }

    @Test
    void severalPatternsAreSeparatedBySemicolons() {
        AgentConfig config = with("com.acme.jalo.*;com.acme.generated.*");

        assertTrue(config.isClassExcluded("com.acme.jalo.Order"));
        assertTrue(config.isClassExcluded("com.acme.generated.Thing"));
        assertFalse(config.isClassExcluded("com.acme.service.OrderService"));
        assertEquals(2, config.getExcludeClasses().size());
    }

    @Test
    void oneClassCanBeNamedExactly() {
        AgentConfig config = with("com.acme.Awkward");

        assertTrue(config.isClassExcluded("com.acme.Awkward"));
        assertFalse(config.isClassExcluded("com.acme.AwkwardToo"));
        assertFalse(config.isClassExcluded("com.acme.other.Awkward"));
    }

    /**
     * A {@code *} matches any characters, dots included, so naming a package
     * covers everything under it. That is what someone writing
     * {@code com.acme.*} means, and it is the same glob the file patterns use.
     */
    @Test
    void namingAPackageCoversWhatIsUnderIt() {
        AgentConfig config = with("com.acme.*");

        assertTrue(config.isClassExcluded("com.acme.Thing"));
        assertTrue(config.isClassExcluded("com.acme.deep.down.Thing"));
        assertFalse(config.isClassExcluded("com.other.Thing"));
    }

    @Test
    void blanksAndEmptiesAreIgnoredRatherThanExcludingEverything() {
        AgentConfig config = with("  ;;  ");

        assertTrue(config.getExcludeClasses().isEmpty());
        assertFalse(config.isClassExcluded("com.acme.Order"));
    }
}
