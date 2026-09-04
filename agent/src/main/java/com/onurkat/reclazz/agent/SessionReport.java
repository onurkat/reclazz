/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.ui.Plural;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What this agent has actually done, since it started.
 *
 * <p>Two questions already have answers on the socket: why a class did not
 * reload, and what still needs a restart. The third one nobody could ask is the
 * plainest: how is this going. A developer whose saves feel slow has no number
 * of their own, only the one in the README, measured on somebody else's machine.
 * A developer who thinks nothing is reloading cannot tell a watcher that never
 * saw their directory from reloads that are landing on code they are not
 * exercising, and the difference is a whole afternoon.
 *
 * <p>Everything here is counted as it happens, so asking costs nothing. The
 * latencies are a bounded ring: the last few hundred reloads say what a save
 * costs today, and keeping every one of them would be a leak in a process this
 * agent is only a guest in.
 */
public final class SessionReport {

    /** Enough for a working session's worth of shape, small enough to forget. */
    private static final int REMEMBERED_LATENCIES = 512;

    /**
     * When the agent started, not when this class was first touched.
     *
     * <p>These are all statics, so the class loads the first time somebody asks
     * for the report, and the first live answer said "up 0 seconds" about an
     * agent that had been running for a minute. The agent sets it; the field
     * keeps a value in case it never does, so the report is wrong by a little
     * rather than absent.
     */
    private static volatile Instant started = Instant.now();

    private static final AtomicInteger bodyReloads = new AtomicInteger();
    private static final AtomicInteger structuralReloads = new AtomicInteger();
    private static final AtomicInteger failures = new AtomicInteger();
    private static final AtomicInteger compiles = new AtomicInteger();
    private static final AtomicLong lastChangeAt = new AtomicLong();

    private static final long[] latencies = new long[REMEMBERED_LATENCIES];
    private static final AtomicInteger latencyCount = new AtomicInteger();

    private SessionReport() {
    }

    /** Called once, when the agent has finished starting. */
    public static void sessionStarted(Instant when) {
        started = when;
    }

    /** A reload that landed. A negative time is one the caller did not measure. */
    public static void reloaded(boolean structural, long millis) {
        (structural ? structuralReloads : bodyReloads).incrementAndGet();
        lastChangeAt.set(System.currentTimeMillis());
        if (millis < 0) return;
        int at = latencyCount.getAndIncrement();
        synchronized (latencies) {
            latencies[at % REMEMBERED_LATENCIES] = millis;
        }
    }

    /** A reload that did not. */
    public static void failed() {
        failures.incrementAndGet();
        lastChangeAt.set(System.currentTimeMillis());
    }

    /** A Java file the agent compiled itself. */
    public static void compiled(int files) {
        compiles.addAndGet(files);
        lastChangeAt.set(System.currentTimeMillis());
    }

    /**
     * The report, one line at a time, ready to print into the tool window the
     * developer is already looking at.
     *
     * @param watchedDirectories how many the watcher registered, or -1 when
     *                           there is no watcher to ask
     * @param unwatchable        how many it could not, which is the number that
     *                           explains a directory that never reloads
     */
    public static List<String> lines(int watchedDirectories, int unwatchable, int needingRestart) {
        return lines(watchedDirectories, unwatchable, needingRestart, -1, false);
    }

    /**
     * @param watchedFiles how many files sit under those directories, or -1
     *                     when nobody counted
     * @param polling      whether this JDK walks and stats them rather than
     *                     being told by the operating system, which is what
     *                     makes the number cost anything
     */
    public static List<String> lines(int watchedDirectories, int unwatchable, int needingRestart,
                                     int watchedFiles, boolean polling) {
        List<String> report = new ArrayList<>();
        int reloads = bodyReloads.get() + structuralReloads.get();

        report.add("Reclazz " + AgentVersion.get() + ", up " + humanDuration(
                Duration.between(started, Instant.now())) + ".");

        if (reloads == 0 && failures.get() == 0) {
            report.add("Nothing has reloaded yet this session.");
        } else {
            report.add(Plural.of(reloads, "reload") + ": "
                    + structuralReloads.get() + " structural, "
                    + bodyReloads.get() + " method bodies"
                    + (failures.get() > 0 ? ", and " + Plural.of(failures.get(), "failure") : "")
                    + ".");
            report.add(latencyLine(reloads));
        }

        if (compiles.get() > 0) {
            report.add(Plural.of(compiles.get(), "Java file") + " compiled by Reclazz itself.");
        }

        if (watchedDirectories >= 0) {
            report.add(Plural.of(watchedDirectories, "directory", "directories") + " watched"
                    + (watchedFiles >= 0 ? ", holding " + Plural.of(watchedFiles, "file") : "")
                    + (unwatchable > 0
                            ? ", and " + unwatchable + " directories that could not be, which is "
                              + "where a save that reloads nothing would be coming from"
                            : "") + ".");
            if (polling && watchedFiles > 0) {
                // Calibrated on two measurements that agree once they are in
                // the same units: a harness registering a real extension tree,
                // 953 directories holding 18,680 files, burned 9.0% of one core
                // across four consecutive thirty-second windows, and the live
                // server's own watcher thread reported 0.79% in JFR, which is a
                // share of all twelve cores on this machine and so 9.5% of one.
                // That is a stat loop running all day for a developer who may
                // not be editing anything, which is worth a sentence and a
                // pointer at the two settings that shorten the walk.
                report.add("This JDK has no native file watching, so it walks every one of them "
                        + "about twice a second whether or not you are editing, at roughly "
                        + String.format("%.1f", watchedFiles / 2000.0)
                        + "% of one core. watchExtensions and excludePatterns shorten the walk.");
            }
        }

        long last = lastChangeAt.get();
        report.add(last == 0
                ? "No file has changed under a watched directory yet."
                : "Last change " + humanDuration(
                        Duration.ofMillis(System.currentTimeMillis() - last)) + " ago.");

        report.add(needingRestart == 0
                ? "Nothing is waiting on a restart."
                : Plural.of(needingRestart, "thing") + " waiting on a restart; ask PENDING for what.");
        return report;
    }

    private static String latencyLine(int reloads) {
        long[] taken;
        int timed = latencyCount.get();
        int count = Math.min(timed, REMEMBERED_LATENCIES);
        if (count == 0) {
            return "None of them was timed, which is the batch path reloading a whole directory.";
        }
        synchronized (latencies) {
            taken = Arrays.copyOf(latencies, count);
        }
        Arrays.sort(taken);

        // How many of them the number is drawn from, because it is usually not
        // all of them and "over the last 26 reloads" beside "379 reloads" reads
        // as a contradiction rather than as an explanation. Two things thin it:
        // the batch path times the batch and not the classes in it, and on the
        // SAP Commerce run this was measured against that was 353 of 379; and
        // the ring only remembers the last few hundred.
        String from = count == reloads
                ? "over all " + count + " of them"
                : "over the last " + count + " of " + reloads;

        return "Save to reloaded: " + taken[count / 2] + "ms median, "
                + taken[(int) Math.max(0, Math.ceil(count * 0.95) - 1)] + "ms at the 95th, "
                + taken[count - 1] + "ms worst, " + from + ".";
    }

    /** Rounded to something a person says out loud rather than reads off a clock. */
    static String humanDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) return Plural.of(seconds, "second");
        long minutes = seconds / 60;
        if (minutes < 60) return Plural.of(minutes, "minute");
        long hours = minutes / 60;
        if (hours < 24) return Plural.of(hours, "hour");
        return Plural.of(hours / 24, "day");
    }

    /** A new session starts counting again, which is what a restart is. */
    static void resetForTests() {
        started = Instant.now();
        bodyReloads.set(0);
        structuralReloads.set(0);
        failures.set(0);
        compiles.set(0);
        lastChangeAt.set(0);
        latencyCount.set(0);
        synchronized (latencies) {
            Arrays.fill(latencies, 0);
        }
    }
}
