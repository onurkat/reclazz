/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An argument this version of the agent does not know.
 *
 * <p>The belief was that unrecognised keys are ignored. They are not. The
 * splitter breaks the argument string on a comma only when a KNOWN key follows
 * it, deliberately, so that a value may contain a comma of its own. An unknown
 * key therefore never becomes an argument: it becomes part of the value of the
 * argument before it. {@code hybrisHome=/srv/hybris,debouceMs=200} parses as a
 * single setting whose hybrisHome is {@code /srv/hybris,debouceMs=200}, and
 * what the developer is then told is about their platform rather than about
 * their typo.
 *
 * <p>Three things arrive this way and none is rare: a typo, an argument copied
 * from notes older than the jar, and a jar older than the argument, which is
 * the ordinary state of a SAP Commerce server whose {@code wrapper.conf} was
 * written by a newer plugin and whose staged jar has not been refreshed.
 *
 * <p>The parse is deliberately unchanged, so these are about saying what
 * happened, not about guessing differently.
 */
class UnknownArgumentsAreNamedTest {

    @Test
    void anArgumentThisVersionKnowsIsNotComplainedAbout() {
        AgentConfig config = AgentConfig.parse(
                "hybrisHome=/srv/hybris,debounceMs=200,autoCompile=true,wrapOutput=never");

        assertEquals(List.of(), config.getUnknownKeys());
        assertEquals("", printed(config::reportUnknownKeys).trim());
    }

    /** The defect these were written to find. */
    @Test
    void aTypoIsSwallowedByTheArgumentBeforeItAndSaidSo() {
        AgentConfig config = AgentConfig.parse("hybrisHome=/srv/hybris,debouceMs=200");

        assertEquals(Paths.get("/srv/hybris,debouceMs=200"), config.getHybrisHome(),
                "this is what actually happens to it, and why saying nothing was wrong");
        assertEquals(List.of("debouceMs"), config.getUnknownKeys(),
                "the misspelling, named, rather than a mystery about the hybris home");
    }

    /** The upgrade case: a newer plugin's argument reaching an older jar. */
    @Test
    void anArgumentFromANewerReleaseIsNamed() {
        AgentConfig config = AgentConfig.parse(
                "debounceMs=250,hybrisHome=/srv/hybris,somethingFromNextYear=true");

        assertEquals(List.of("somethingFromNextYear"), config.getUnknownKeys());
        assertEquals(250, config.getDebounceMs(),
                "the arguments before the unknown one are untouched");
    }

    /** One that comes first does become its own argument, and is still not known. */
    @Test
    void anUnknownKeyInFrontIsNamedToo() {
        AgentConfig config = AgentConfig.parse("debouceMs=200,hybrisHome=/srv/hybris");

        assertEquals(List.of("debouceMs"), config.getUnknownKeys());
        assertEquals(Paths.get("/srv/hybris"), config.getHybrisHome(),
                "and here it costs nothing, which is why the message says it depends");
    }

    @Test
    void theMessageSaysWhatHappenedAndWhatItWouldHaveAccepted() {
        AgentConfig config = AgentConfig.parse(
                "hybrisHome=/srv/hybris,debouceMs=200,autoCompil=true");

        String said = printed(config::reportUnknownKeys);

        assertTrue(said.contains("debouceMs") && said.contains("autoCompil"),
                () -> "both, so a developer does not fix one and re-run for the other: " + said);
        assertTrue(said.contains("debounceMs") && said.contains("autoCompile"),
                () -> "and the names it does know, which is what turns this into a fix: " + said);
        assertTrue(said.contains("value of the argument before"),
                () -> "and the consequence, which is the part nobody would guess: " + said);
    }

    @Test
    void oneIsSaidInTheSingular() {
        AgentConfig config = AgentConfig.parse("hybrisHome=/srv/hybris,verbse=true");

        String said = printed(config::reportUnknownKeys);

        assertTrue(said.contains("argument is not one"), () -> said);
        assertFalse(said.contains("arguments are not"), () -> said);
    }

    /**
     * A value that is allowed a comma keeps it. This is the reason the splitter
     * works the way it does, and the reason the fix is a warning rather than a
     * different split.
     */
    @Test
    void aValueWithCommasInItIsNotMistakenForArguments() {
        // Every list-valued argument separates on a semicolon, so a comma
        // inside a value is part of the value: a directory name, or a glob.
        AgentConfig config = AgentConfig.parse(
                "excludePatterns=**/gen,erated/**;**/Test*.java,debounceMs=300");

        assertEquals(List.of(), config.getUnknownKeys(),
                "a comma that is not followed by an argument name is just a comma");
        assertEquals(2, config.getExcludePatterns().size(),
                () -> "the pattern list lost or gained an entry: " + config.getExcludePatterns());
        assertEquals("**/gen,erated/**", config.getExcludePatterns().get(0),
                "and the comma stayed inside the pattern it belongs to");
        assertEquals(300, config.getDebounceMs());
    }

    /** Nothing to say about no arguments at all. */
    @Test
    void noArgumentsIsNotAComplaint() {
        assertEquals(List.of(), AgentConfig.parse(null).getUnknownKeys());
        assertEquals(List.of(), AgentConfig.parse("").getUnknownKeys());
    }

    private static String printed(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
