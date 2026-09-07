/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gates in front of the persistence unit rebuild.
 *
 * <p>The rebuild closes every open persistence context and runs the schema
 * action against the live database, so the failure these tests prevent is it
 * running for someone who did not ask, on a VM where it cannot work, or with a
 * schema setting where the refreshed mapping would point at a column that does
 * not exist. In every one of those cases the developer must get exactly the
 * warning they got before the feature existed.
 */
class JpaMappingRefreshTest {

    /**
     * A rebuild that tears down persistence contexts must never be a surprise
     * in an agent upgrade: without the flag, parsing the same arguments as
     * yesterday has to leave the feature off.
     */
    @Test
    void theRebuildIsOffByDefault() {
        assertFalse(AgentConfig.parse(null).isJpaRefresh());
        assertFalse(AgentConfig.parse("").isJpaRefresh());
        assertFalse(AgentConfig.parse("verbose=true,debounceMs=200").isJpaRefresh());
    }

    @Test
    void theOptInIsAnOrdinaryAgentArgument() {
        assertTrue(AgentConfig.parse("jpaRefresh=true").isJpaRefresh());
        assertTrue(AgentConfig.parse("verbose=true,jpaRefresh=true,debounceMs=200").isJpaRefresh(),
                "the key has to be in the splitter's known set, or a preceding "
                + "path value would swallow it");
        assertFalse(AgentConfig.parse("jpaRefresh=false").isJpaRefresh());
    }

    /**
     * This test JVM is a stock JDK with no agent probe, which is exactly the
     * capability-gate case: on such a VM the loaded class never physically
     * gains the field, so a rebuilt metamodel could not see it either. The
     * decline has to be silent, leaving the warning byte for byte what it was.
     */
    @Test
    void withoutEnhancedRedefinitionTheDeclineIsSilent() {
        var change = new JpaEntityChange.Change(java.util.List.of("currency"), java.util.List.of());

        var result = JpaMappingRefresh.apply("demo.Order", String.class, change);

        assertFalse(result.refreshed());
        assertEquals("", result.appendix(),
                "a hint about jpaRefresh=true here would send the developer to a "
                + "flag that cannot help on this VM");
    }

    /**
     * The schema settings where the rebuild itself creates the column are the
     * only ones where a refreshed mapping is a complete fix. At validate the
     * fresh factory refuses its own startup, and at none the next query hits a
     * column that is not there.
     */
    @Test
    void onlyTheSchemaCreatingSettingsQualify() {
        assertTrue(JpaMappingRefresh.ddlAutoQualifies("update"));
        assertTrue(JpaMappingRefresh.ddlAutoQualifies("create"));
        assertTrue(JpaMappingRefresh.ddlAutoQualifies("create-drop"));

        assertFalse(JpaMappingRefresh.ddlAutoQualifies("validate"),
                "rebuilding here would fail against the missing column");
        assertFalse(JpaMappingRefresh.ddlAutoQualifies("none"));
        assertFalse(JpaMappingRefresh.ddlAutoQualifies(null),
                "an unreadable setting is not permission to write DDL");
        assertFalse(JpaMappingRefresh.ddlAutoQualifies("something-new"));
    }

    /**
     * The hint is the only wording the feature may add to the old warning, and
     * only in the case where the flag is genuinely the one thing missing. It
     * has to name the exact argument, or it is a scavenger hunt.
     */
    @Test
    void theHintNamesTheExactFlag() {
        assertTrue(JpaMappingRefresh.OPT_IN_HINT.contains("jpaRefresh=true"),
                JpaMappingRefresh.OPT_IN_HINT);
        assertTrue(JpaMappingRefresh.OPT_IN_HINT.contains("rebuild the persistence unit"),
                "it has to say what the flag does, not just its name");
    }

    /**
     * The decline path must still produce the pre-feature warning. The wording
     * lives in JpaEntityChange and JpaSchemaAdvice, and their own tests hold
     * it; this holds the integration: report() consults the refresh before
     * warning, so a decline with an empty appendix is the old message exactly.
     */
    @Test
    void aDeclinedRefreshLeavesTheOldWarningIntact() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/onurkat/reclazz/reload/JpaEntityChange.java"));

        int refreshAt = source.indexOf("JpaMappingRefresh.apply");
        int warnAt = source.indexOf("persistence mapping still has the old shape");
        assertTrue(refreshAt > 0 && warnAt > 0, "both halves have to be there");
        assertTrue(refreshAt < warnAt,
                "the refresh has to be consulted before the warning, so a "
                + "successful rebuild does not also print the stale-mapping text");
        assertTrue(source.contains("refresh.appendix()"),
                "the warning has to carry the appendix, or the qualifying-but-"
                + "opted-out case loses its hint");
    }

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final StatusReporter.StatusListener listener = (level, message) -> lines.add(level + " " + message);

    @AfterEach
    void unconfigure() {
        JpaMappingRefresh.configure(null, null);
        StatusReporter.removeListener(listener);
    }

    /**
     * The opt-in and the JVM's ability are handed in by whoever assembles
     * the agent, not read off its static state: with the opt-in said to be
     * on, a new entity gets past the hint and is declined for the next real
     * reason, which in a JVM with no Spring context is that there is no
     * persistence unit to rebuild.
     */
    @Test
    void theOptInIsReadFromWhoeverConfiguredIt() {
        StatusReporter.addListener(listener);
        JpaMappingRefresh.configure(() -> true, () -> false);

        JpaMappingRefresh.applyForNewEntity("demo.Thing", Object.class);

        assertTrue(lines.stream().noneMatch(l -> l.contains("jpaRefresh=true")),
                "the hint to opt in is for developers who have not: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("WARN") && l.contains("not exactly one persistence unit")),
                "declined for the next reason along, so the opt-in was seen: " + lines);
    }

    /** Unconfigured, both conditions say no, which is what a JVM without the agent's start-up gets. */
    @Test
    void unconfiguredItDeclinesWithTheHint() {
        StatusReporter.addListener(listener);
        JpaMappingRefresh.configure(null, null);

        JpaMappingRefresh.applyForNewEntity("demo.Thing", Object.class);

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("INFO") && l.contains("jpaRefresh=true")),
                "the hint names the flag: " + lines);
    }
}
