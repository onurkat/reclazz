/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explains the metaspace a long reloading session uses up, before it runs out.
 *
 * <p>Redefining a class costs metaspace that is never given back. The JVM keeps
 * the previous version of the class so that a thread still running its old code
 * keeps working, and it purges those versions lazily and incompletely. Measured
 * on stock JDK 21 with no agent involved at all, a bare
 * {@code Instrumentation.redefineClasses} loop on a one-method class:
 *
 * <pre>
 *   after 10 redefinitions   metaspace 10284 KB
 *   after 60 redefinitions   metaspace 10814 KB     about 10.6 KB each
 * </pre>
 *
 * <p>Reclazz measured slightly under that, 8.5 KB per reload, and its own
 * companion classes are collected: the JVM's unloaded-class counter rises one
 * for one with the reloads, which is what CompanionsAreCollectedTest pins. So
 * this is not Reclazz's leak to fix. It is Reclazz's to explain, because
 * Reclazz is the reason a developer is redefining classes hundreds of times in
 * one JVM instead of restarting.
 *
 * <p>It matters where metaspace is capped, and on SAP Commerce it always is.
 * Without this, a session that has been going all day ends in
 * {@code OutOfMemoryError: Metaspace} with a stack trace pointing at whatever
 * innocent code happened to load the next class, and nothing connecting it to
 * the reloading. So the warning arrives while there is still room, says how
 * many reloads it took, and says the one thing that fixes it.
 *
 * <p>Silent when metaspace is uncapped, which is the default outside a
 * container: there the growth is bounded by the machine and a warning would be
 * noise. Silent below the first threshold, and once per threshold after that.
 */
public final class MetaspaceWatch {

    /** Percentages worth interrupting for, in the order they are crossed. */
    private static final int[] THRESHOLDS = {85, 95};

    private static final AtomicInteger reloads = new AtomicInteger();

    /** The highest threshold already reported, so each is said once. */
    private static volatile int reported = 0;

    private MetaspaceWatch() {
    }

    /** Called once per reload batch, whatever the batch did. */
    public static void afterReload() {
        int count = reloads.incrementAndGet();
        try {
            long used = 0;
            long max = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getName().contains("Metaspace")) {
                    used = pool.getUsage().getUsed();
                    max = pool.getUsage().getMax();
                    break;
                }
            }
            int crossed = thresholdCrossed(used, max, reported);
            if (crossed == 0) return;
            reported = crossed;

            StatusReporter.warn("Metaspace is " + crossed + "% full after " + count
                    + " reloads this session. Redefining a class costs metaspace that the JVM "
                    + "never gives back: it keeps the previous version of the class so threads "
                    + "still running the old code keep working. That cost is the JVM's, not "
                    + "Reclazz's, and nothing but a restart clears it. Restarting now is cheaper "
                    + "than an OutOfMemoryError in the middle of something.");
            RestartLedger.note("this session",
                    "metaspace is " + crossed + "% full after " + count + " reloads, and the "
                    + "JVM does not return what redefining a class costs");
        } catch (Throwable cannotTell) {
            // A memory pool that will not say what it holds produces no claim.
        }
    }

    /**
     * @param used     metaspace currently in use
     * @param max      its ceiling, or a non-positive number when it has none
     * @param reported the highest threshold already said out loud
     * @return the threshold to report now, or 0 for nothing to say
     */
    static int thresholdCrossed(long used, long max, int reported) {
        if (max <= 0 || used <= 0) return 0;
        long percent = used * 100 / max;
        int crossed = 0;
        for (int threshold : THRESHOLDS) {
            if (percent >= threshold && threshold > reported) crossed = threshold;
        }
        return crossed;
    }

    /** How many reload batches this session has run. */
    public static int reloadCount() {
        return reloads.get();
    }
}
