/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a process that is not allowed to hold the reload thread forever.
 *
 * <p>Reading a process's output to the end and then waiting on it with a
 * timeout is a timeout in name only: a process that hangs with its output
 * open never reaches the end, so the read never returns and the wait is never
 * reached. The output is pumped on a thread of its own here, and the wait is
 * the thing that bounds the call. On timeout the process is killed, and what
 * it printed last is kept so the report can say what it was doing.
 */
public final class BoundedProcess {

    /** How it ended: the exit code (or -1 on timeout), whether it was killed, its last output. */
    public record Result(int exitCode, boolean timedOut, String tail) {
    }

    private BoundedProcess() {
    }

    /**
     * @param onLine    sees every output line as it arrives (stderr merged in)
     * @param tailChars how much of the end of the output to keep for the result
     */
    public static Result run(ProcessBuilder builder, Duration timeout,
                             Consumer<String> onLine, int tailChars)
            throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder tail = new StringBuilder();
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                    synchronized (tail) {
                        tail.append(line).append('\n');
                        if (tail.length() > tailChars) {
                            tail.delete(0, tail.length() - tailChars);
                        }
                    }
                }
            } catch (IOException ended) {
                // The process is gone, and so is its output.
            }
        }, "Reclazz-ProcessOutput");
        pump.setDaemon(true);
        pump.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            pump.join(1000);
            synchronized (tail) {
                return new Result(-1, true, tail.toString());
            }
        }
        pump.join(5000);
        synchronized (tail) {
            return new Result(process.exitValue(), false, tail.toString());
        }
    }
}
