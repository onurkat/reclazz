/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/**
 * Reloads as JDK Flight Recorder events.
 *
 * <p>The status socket tells the IDE what happened in words; this tells the
 * JVM's own recorder, so a reload sits on the same timeline as the garbage
 * collections, safepoints and compilations around it. A developer asking why
 * the server paused, or a maintainer sent a recording of a slow session, sees
 * {@code reclazz.Reload} events in JDK Mission Control or {@code jfr print}
 * with the class, the kind, the duration, what changed and which file the
 * bytes came from, and {@code reclazz.ReloadFailed} with the reason.
 *
 * <p>Emitted by the agent where the reload lands, not by the console
 * reporter: the reporter prints a display name and knows no file, and a
 * recording is the thing a reload is traced back from.
 *
 * <p>{@code jdk.jfr} is part of every JDK this agent runs on, but a runtime
 * image built without it is possible, so the event classes live in a nested
 * holder that is only touched when they are needed, and the first
 * {@link LinkageError} switches this off for the session rather than
 * failing a reload over its recording.
 */
public final class ReloadEvents {

    private static volatile boolean available = true;

    private ReloadEvents() {
    }

    /**
     * @param className the class as the JVM names it, {@code Outer$Inner}
     *                  included: the recording is where a reload is traced
     *                  back from, and the console's "(inner class)" form
     *                  names three anonymous classes the same way
     * @param millis    the measured duration, or negative when this reload
     *                  was one of a batch timed as a whole
     * @param shape     what changed, as "v2, +1 method", or null
     * @param source    where the bytes came from, the class file or the
     *                  source file that was compiled, or null
     */
    public static void reloaded(String className, boolean structural, long millis, String shape,
                                String source) {
        if (!available) return;
        try {
            Jfr.reloaded(className, structural, millis, shape, source);
        } catch (LinkageError noFlightRecorder) {
            available = false;
        }
    }

    public static void failed(String className, String reason, String source) {
        if (!available) return;
        try {
            Jfr.failed(className, reason, source);
        } catch (LinkageError noFlightRecorder) {
            available = false;
        }
    }

    /** Only loaded when a reload happens on a JVM that has JFR. */
    private static final class Jfr {

        @Name("reclazz.Reload")
        @Label("Reclazz Reload")
        @Category("Reclazz")
        @Description("A class was hot-reloaded into the running JVM")
        static final class Reload extends Event {
            @Label("Class")
            String className;
            @Label("Structural")
            @Description("Whether members were added or removed, as opposed to method bodies changing")
            boolean structural;
            // Not "duration": JFR reserves that name (with startTime,
            // eventThread and stackTrace) for its own fields, and an event
            // class that declares it never registers, so it records nothing
            // and says nothing. Measured: the failure event arrived, the
            // reload events did not.
            @Label("Measured Duration")
            @Timespan(Timespan.MILLISECONDS)
            @Description("How long the reload took, or -1 when it was one of a batch timed as a whole")
            long measured;
            @Label("Shape")
            @Description("What changed, when the reload can say")
            String shape;
            @Label("Source")
            @Description("The class file, or the source file that was compiled, the bytes came from")
            String source;
        }

        @Name("reclazz.ReloadFailed")
        @Label("Reclazz Reload Failed")
        @Category("Reclazz")
        @Description("A class could not be hot-reloaded")
        static final class ReloadFailed extends Event {
            @Label("Class")
            String className;
            @Label("Reason")
            String reason;
            @Label("Source")
            @Description("The class file, or the source file that was compiled, the bytes came from")
            String source;
        }

        static void reloaded(String className, boolean structural, long millis, String shape,
                             String source) {
            Reload event = new Reload();
            if (!event.isEnabled()) return;
            event.className = className;
            event.structural = structural;
            event.measured = millis;
            event.shape = shape == null ? "" : shape;
            event.source = source == null ? "" : source;
            event.commit();
        }

        static void failed(String className, String reason, String source) {
            ReloadFailed event = new ReloadFailed();
            if (!event.isEnabled()) return;
            event.className = className;
            event.reason = reason == null ? "" : reason;
            event.source = source == null ? "" : source;
            event.commit();
        }
    }
}
