/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * A record of what the agent did, in a file.
 *
 * <p>The console is where the agent talks, and the console scrolls away, is
 * mixed with the application's own logging, and is gone when the server is
 * restarted. HEALTH and DIAGNOSE answer from memory, bounded and lost with
 * the JVM. Asked for with {@code sessionLog=<path>}, this appends every
 * status line the agent emits, one per line, with an ISO timestamp, the
 * level and the message, no colour codes and no wrapping: a session's audit
 * trail, to read back after the fact or to attach to a report. Appending,
 * so restarts of the same server add to one file rather than replacing it.
 *
 * <p>A file that cannot be written costs the record, not the reload: the
 * failure is said once and the listener falls silent.
 */
public final class SessionLog implements StatusReporter.StatusListener {

    private final Path file;
    private volatile BufferedWriter out;
    private volatile boolean gaveUp;

    public SessionLog(Path file) {
        this.file = file;
    }

    /** Open the file and announce the session; returns null when the file cannot be written. */
    public static SessionLog open(Path file, String agentVersion) {
        SessionLog log = new SessionLog(file);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            log.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.write("SESSION", "Reclazz " + agentVersion + " started in JVM "
                    + ProcessHandle.current().pid() + ", " + System.getProperty("java.vm.name", "")
                    + " " + System.getProperty("java.version", ""));
            return log;
        } catch (IOException e) {
            StatusReporter.warn("Session log " + file + " cannot be written (" + Failures.describe(e)
                    + "); the session is not being recorded.");
            return null;
        }
    }

    @Override
    public void onEvent(String level, String message) {
        write(level, message);
    }

    private synchronized void write(String level, String message) {
        if (gaveUp || out == null) return;
        try {
            out.write(Instant.now().toString());
            out.write(' ');
            out.write(level.trim());
            out.write(' ');
            out.write(message == null ? "" : message.replace('\n', ' '));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            gaveUp = true;
            StatusReporter.warn("Session log " + file + " stopped: " + Failures.describe(e));
        }
    }

    public Path file() {
        return file;
    }
}
