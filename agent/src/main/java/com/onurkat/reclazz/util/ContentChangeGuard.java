/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Answers whether a file's bytes differ from the last time it was acted on.
 *
 * A save event is not evidence that anything changed. Editors write on a timer,
 * a reformat rewrites the same text, and the platform's own build re-copies
 * resource files wholesale, so a single {@code ant build} can fire a modify
 * event for every localization file in every extension without a character
 * being different.
 *
 * That matters where the work a save triggers is expensive. Clearing a cache is
 * instant, but the next reader pays for the rebuild: measured on a 2211 server,
 * the first lookup after clearing the platform's localization cache took 830ms,
 * and after clearing ZK's label cache just under four seconds. Paying that for
 * a file that did not change is the kind of cost that gets blamed on the tool.
 *
 * CRC32 over the file, the same choice the class-file dedupe in FileWatcher
 * makes: the key space is a few thousand files, and the hash costs far less
 * than what it is protecting against.
 */
public final class ContentChangeGuard {

    /**
     * Files this size or larger are not read at all. The agent lives inside a
     * long-running server and the file is whatever landed in a watched
     * directory, so its size is not ours to assume: reading it whole to hash it
     * would let one stray file take the heap out from under the application it
     * is supposed to be helping. A localization file that big is not one either.
     */
    private static final long MAX_HASHED_BYTES = 8L * 1024 * 1024;

    /**
     * The agent runs for days and this map only ever grows, one entry per file
     * seen. Thousands is already far past any real project, so past that it
     * starts again rather than growing without limit; the cost of forgetting is
     * one unnecessary reload per file.
     */
    private static final int MAX_ENTRIES = 4096;

    private final Map<Path, Long> lastSeen = new ConcurrentHashMap<>();

    /**
     * @return true when this file has changed since the last call that returned
     *         true, when it has never been seen, or when it cannot be read.
     *         Unreadable means mid-write, and a save that is still in flight is
     *         a change, not a reason to skip.
     */
    public boolean changed(Path file) {
        long hash = hash(file);
        if (hash == -1L) return true;

        if (lastSeen.size() >= MAX_ENTRIES) lastSeen.clear();

        Long previous = lastSeen.put(file, hash);
        return previous == null || previous != hash;
    }

    /** Test seam: forget everything, as a fresh agent would. */
    public void clear() {
        lastSeen.clear();
    }

    private static long hash(Path file) {
        try {
            if (Files.size(file) >= MAX_HASHED_BYTES) return -1L;

            // Streamed, so the whole file is never resident. readAllBytes would
            // hold a copy the size of the file on top of the file itself.
            CRC32 crc = new CRC32();
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    crc.update(buffer, 0, read);
                }
            }
            return crc.getValue();
        } catch (IOException e) {
            return -1L;
        }
    }
}
