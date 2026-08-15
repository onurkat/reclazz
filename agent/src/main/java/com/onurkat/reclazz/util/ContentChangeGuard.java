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

        Long previous = lastSeen.put(file, hash);
        return previous == null || previous != hash;
    }

    /** Test seam: forget everything, as a fresh agent would. */
    public void clear() {
        lastSeen.clear();
    }

    private static long hash(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            return crc.getValue();
        } catch (IOException | OutOfMemoryError e) {
            return -1L;
        }
    }
}
