/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.StatusReporter;

/**
 * Work whose death would otherwise be silent, and permanent.
 *
 * <p>The agent runs nine threads inside somebody else's application, and the
 * two that matter are submitted to an executor with {@code submit}. That
 * returns a {@code Future} which captures anything the task throws, and nobody
 * holds these futures. The file watcher is one of them: a runtime exception
 * anywhere in {@code startWatching} ended the thread, was stored in an object
 * that was immediately discarded, and left a session where saving a file does
 * nothing at all, for as long as the server stays up, with no line anywhere
 * saying so. The IDE kept showing a connected agent, because the heartbeat is a
 * different thread and it was still fine.
 *
 * <p>That shape is the failure this project keeps having to hunt down: not a
 * crash, which someone would report, but a quiet stop that reads exactly like
 * "my change did not need a reload". So nothing here is allowed to end without
 * saying so, and what it says is the consequence rather than the stack: the
 * developer's question is not what threw, it is whether their next save is
 * going to do anything.
 *
 * <p>It does not restart anything. Whatever killed a watcher is likely to kill
 * its replacement, and a loop that dies and respawns forever is a worse thing
 * to give somebody than a sentence telling them to restart.
 */
public final class Supervised {

    private Supervised() {
    }

    /**
     * Wraps work that should run for the life of the session.
     *
     * @param what        the thing that stopped, in the developer's terms
     * @param consequence what that means for them, and what to do
     */
    public static Runnable forever(String what, String consequence, Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Throwable t) {
                // Throwable: an Error is exactly what a task like this dies of,
                // and catching Exception is how it stayed invisible.
                report(what, consequence, t);
            }
        };
    }

    /**
     * Wraps one piece of work among many. A failure here costs that piece, not
     * the session, so it is reported without the talk of restarting.
     */
    public static Runnable once(String what, Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Throwable t) {
                StatusReporter.error(what + " failed: " + Failures.describe(t));
            }
        };
    }

    /** Also for a run that ends quietly when it was supposed to keep going. */
    public static void stoppedUnexpectedly(String what, String consequence) {
        StatusReporter.error(what + " stopped. " + consequence);
        com.onurkat.reclazz.agent.RestartLedger.note(what, "it stopped during this session");
    }

    private static void report(String what, String consequence, Throwable t) {
        StatusReporter.error(what + " stopped: " + Failures.describe(t) + ". " + consequence);
        com.onurkat.reclazz.agent.RestartLedger.note(what,
                "it stopped during this session with " + t.getClass().getSimpleName());
    }
}
