/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One framework failing is one framework failing.
 *
 * <p>Ten things are told about every reloaded Spring bean, and they were a
 * bare sequence of calls. Each of them asks a class the agent did not compile
 * against what methods and annotations it has, in an application whose Spring
 * version the agent has never seen, so the first question is exactly where a
 * missing optional module surfaces, as a {@code NoClassDefFoundError}. An
 * Error is not an Exception: it walked out of the sequence, past the nine
 * remaining steps, and reached a catch that told the developer their class
 * file could not be read. It could. The caches were not evicted, the scheduled
 * methods not re-registered, the security configuration not rebuilt, and
 * nothing said so.
 *
 * <p>These are about the mechanism rather than any one framework, because the
 * mechanism is what a thirteenth integration will inherit.
 */
class ReloadStepsTest {

    @BeforeEach
    void freshSession() {
        ReloadSteps.resetForTests();
    }

    private static final ReloadSteps.Reloaded TARGET =
            new ReloadSteps.Reloaded("app.Service", ReloadStepsTest.class, true, false);

    @Test
    void everyStepRunsWhenNothingGoesWrong() {
        List<String> ran = new ArrayList<>();

        int completed = ReloadSteps.runAll(List.of(
                new ReloadSteps.Step("first", r -> ran.add("first")),
                new ReloadSteps.Step("second", r -> ran.add("second")),
                new ReloadSteps.Step("third", r -> ran.add("third"))), TARGET);

        assertEquals(List.of("first", "second", "third"), ran, "and in the order given");
        assertEquals(3, completed);
    }

    /** The whole point: what comes after a failure still happens. */
    @Test
    void aStepThatThrowsDoesNotTakeTheOnesAfterItWithIt() {
        List<String> ran = new ArrayList<>();

        int completed = printed(() -> ReloadSteps.runAll(List.of(
                new ReloadSteps.Step("first", r -> ran.add("first")),
                new ReloadSteps.Step("second", r -> {
                    throw new IllegalStateException("this framework is unhappy");
                }),
                new ReloadSteps.Step("third", r -> ran.add("third"))), TARGET)).value();

        assertEquals(List.of("first", "third"), ran,
                "the step after the failing one is where the old sequence stopped");
        assertEquals(2, completed);
    }

    /**
     * The failure this was built for. An optional Spring module that is not on
     * the classpath answers with an Error, and catching Exception would let it
     * straight back out.
     */
    @Test
    void anErrorIsContainedToo() {
        List<String> ran = new ArrayList<>();

        printed(() -> ReloadSteps.runAll(List.of(
                new ReloadSteps.Step("Data repository refresh", r -> {
                    throw new NoClassDefFoundError("org/springframework/data/repository/Repository");
                }),
                new ReloadSteps.Step("Security configuration", r -> ran.add("security"))), TARGET));

        assertEquals(List.of("security"), ran,
                "a NoClassDefFoundError from one optional module must not stop the rest");
    }

    @Test
    void theFailureIsNamedInTheDevelopersTerms() {
        String said = printed(() -> ReloadSteps.runAll(List.of(
                new ReloadSteps.Step("Scheduler re-registration", r -> {
                    throw new IllegalStateException("no TaskScheduler");
                })), TARGET)).output();

        assertTrue(said.contains("Scheduler re-registration"),
                () -> "the step has to say which one it was: " + said);
        assertTrue(said.contains("app.Service"),
                () -> "and for which class: " + said);
        assertTrue(said.contains("rest of the reload continued"),
                () -> "and that the reload was not lost with it: " + said);
    }

    /**
     * A framework that is broken for this application is broken on every save.
     * The same warning on every keystroke is the thing that makes people stop
     * reading the output.
     */
    @Test
    void aBrokenFrameworkIsNamedOnceRatherThanOnEverySave() {
        List<ReloadSteps.Step> steps = List.of(
                new ReloadSteps.Step("AOP proxy cache clear", r -> {
                    throw new NoClassDefFoundError("org/aspectj/lang/JoinPoint");
                }));

        String first = printed(() -> ReloadSteps.runAll(steps, TARGET)).output();
        String second = printed(() -> ReloadSteps.runAll(steps, TARGET)).output();
        String third = printed(() -> ReloadSteps.runAll(steps, TARGET)).output();

        assertTrue(first.contains("AOP proxy cache clear"), "the first save says it");
        assertEquals("", second.trim(), "and the second does not");
        assertEquals("", third.trim(), "nor the third");
    }

    /** A different failure in the same step is a different thing to know. */
    @Test
    void anotherFailureInTheSameStepIsStillWorthSaying() {
        printed(() -> ReloadSteps.runAll(List.of(new ReloadSteps.Step("Cache eviction",
                r -> { throw new IllegalStateException("one"); })), TARGET));

        String said = printed(() -> ReloadSteps.runAll(List.of(new ReloadSteps.Step("Cache eviction",
                r -> { throw new NoClassDefFoundError("two"); })), TARGET)).output();

        assertTrue(said.contains("Cache eviction"),
                () -> "the step failed a second way and that is news: " + said);
    }

    private record Captured(int value, String output) {
    }

    private static Captured printed(java.util.function.Supplier<Integer> action) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            return new Captured(action.get(),
                    captured.toString(StandardCharsets.UTF_8)
                            .replaceAll("\\e\\[[0-9;]*m", ""));
        } finally {
            System.setOut(original);
        }
    }

    private static void printed(Runnable action) {
        printed(() -> {
            action.run();
            return 0;
        });
    }
}
