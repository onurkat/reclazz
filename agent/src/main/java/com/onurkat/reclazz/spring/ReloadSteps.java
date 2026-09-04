/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.StatusReporter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The things a framework has to be told after a class is reloaded, run one by
 * one, and separately.
 *
 * <p>Twelve of them run for every reloaded Spring bean: evict the caches, drop
 * the cached answer to what an annotation says, re-register the scheduled
 * methods, the event listeners, the AOP proxies, the async methods, the
 * repositories, the security configuration. They were a bare sequence of
 * calls, so the reliability of all twelve was the reliability of the least
 * reliable of them. Each of these asks a class it did not compile against what
 * methods and annotations it has, in an application whose Spring version this
 * agent has never seen; the first question can throw a
 * {@code NoClassDefFoundError} for an optional dependency, and an Error is not
 * an Exception, so it walked out through the whole sequence. Everything after
 * the failing step was skipped, in silence, and the line the developer got
 * said their class file could not be read, which it could.
 *
 * <p>Now a step that throws is one step that did not run, named, and the ones
 * after it still do. Which also makes the list the place to add a thirteenth:
 * a name and a lambda, in the order it belongs.
 *
 * <p>A framework that is broken for this application is broken on every save,
 * so each step says so once. Told again after a restart, which is when it is
 * worth hearing again.
 */
public final class ReloadSteps {

    /**
     * One framework's answer to a reload.
     *
     * @param name what to call it when it fails, in the developer's terms
     *             rather than the class's
     */
    public record Step(String name, Consumer<Reloaded> action) {
    }

    /** What the steps are told about the class that just reloaded. */
    public record Reloaded(String className, Class<?> type,
                           boolean structural, boolean annotationsChanged) {
    }

    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private ReloadSteps() {
    }

    /**
     * Runs every step, whatever any one of them does.
     *
     * @return how many ran without throwing, which is what the tests read
     */
    public static int runAll(List<Step> steps, Reloaded target) {
        int completed = 0;
        for (Step step : steps) {
            if (run(step, target)) completed++;
        }
        return completed;
    }

    private static boolean run(Step step, Reloaded target) {
        try {
            step.action().accept(target);
            return true;
        } catch (Throwable t) {
            // Throwable: the failure this exists for is a NoClassDefFoundError
            // from an optional Spring module, and catching Exception would let
            // exactly that one through.
            if (reported.add(step.name() + " " + t.getClass().getName())) {
                StatusReporter.warn(step.name() + " did not run for "
                        + target.className() + ": " + Failures.describe(t)
                        + ". The rest of the reload continued, and this is said once.");
            }
            return false;
        }
    }

    /** A new session says it again, which is what a restart is for. */
    public static void resetForTests() {
        reported.clear();
    }
}
