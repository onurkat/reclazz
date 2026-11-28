/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform.codegen;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class GeneratedBuildTransaction {
    public record Candidate(List<GeneratedPublication.Change> outputs, int classes) { }
    public record Result(boolean success, int fastClasses, String fullReason) { }

    @FunctionalInterface
    public interface Stage {
        Candidate prepare() throws IOException;
    }

    /** Both paths run synchronously while the reloader holds the watcher admission. */
    public static Result run(java.nio.file.Path platform, Stage stage, BooleanSupplier full) throws IOException {
        return run(GeneratedPublication.installation(platform), stage, full);
    }

    static Result run(GeneratedPublication publication, Stage stage, BooleanSupplier full) throws IOException {
        try (var lease = publication.acquire()) {
            publication.recover();
            try {
                Candidate generated = stage.prepare();
                publication.publish(generated.outputs());
                return new Result(true, generated.classes(), null);
            } catch (IOException | RuntimeException recoverable) {
                // Also handles a failure after the journal was installed but before publish() entered its loop.
                // A damaged or concurrently modified generation makes recover() throw, preserving the journal.
                publication.recover();
                String reason = recoverable.getMessage();
                if (reason == null || reason.isBlank()) reason = recoverable.getClass().getName();
                return new Result(full.getAsBoolean(), 0, reason);
            }
        }
    }
}
