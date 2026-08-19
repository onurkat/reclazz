/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The last bytecode the transformer emitted for each watched class.
 *
 * <p>This exists for one consumer: the per-method salvage of a save that also
 * changed the superclass. A method body that needs the new superclass cannot
 * be applied, and the honest alternative to refusing the whole save is to pin
 * that one method to the implementation it had. The previous implementation
 * lives in the {@code __reclazz$v0$...} copies of whatever the transformer
 * last emitted, so that is what is kept here, written on the way out of every
 * successful transform. Nothing is captured retroactively: a class that was
 * never transformed has no entry, and the salvage refuses rather than guesses.
 *
 * <p>The bytes are deflated because the natural lifetime of this cache is the
 * whole JVM and the natural population is every watched class the server
 * loads. Class files deflate well (they are mostly constant-pool text), and
 * the running total is kept so the real cost on a real server can be read
 * from one log line instead of estimated.
 */
public final class TransformedClassCache {

    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicLong DEFLATED_TOTAL = new AtomicLong();

    private record Entry(byte[] deflated, int rawLength) {
    }

    private TransformedClassCache() {
    }

    /** Remember {@code bytes} as the latest emitted class file for {@code internalName}. */
    public static void put(String internalName, byte[] bytes) {
        if (internalName == null || bytes == null) return;
        byte[] deflated = deflate(bytes);
        Entry previous = ENTRIES.put(internalName, new Entry(deflated, bytes.length));
        long delta = deflated.length - (previous == null ? 0 : previous.deflated.length);
        DEFLATED_TOTAL.addAndGet(delta);
    }

    /** The latest emitted class file, or null when this class was never transformed. */
    public static byte[] get(String internalName) {
        Entry entry = ENTRIES.get(internalName);
        if (entry == null) return null;
        return inflate(entry.deflated, entry.rawLength);
    }

    public static int classCount() {
        return ENTRIES.size();
    }

    /** Total deflated payload in bytes, the number the memory measurement reads. */
    public static long deflatedBytes() {
        return DEFLATED_TOTAL.get();
    }

    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 3 + 32);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] deflated, int rawLength) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(deflated);
            byte[] out = new byte[rawLength];
            int written = 0;
            while (written < rawLength && !inflater.finished()) {
                int n = inflater.inflate(out, written, rawLength - written);
                if (n == 0 && inflater.needsInput()) break;
                written += n;
            }
            if (written != rawLength) {
                // A cache that hands back a truncated class file would fail
                // somewhere far from here; a missing entry fails at the guard
                // that already exists for it.
                return null;
            }
            return out;
        } catch (java.util.zip.DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
