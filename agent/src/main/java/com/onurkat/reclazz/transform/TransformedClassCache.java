/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>The bytes are deflated because class files deflate well (they are mostly
 * constant-pool text), and the running total is kept so the real cost on a
 * real server can be read from one log line instead of estimated.
 *
 * <p>The cache is bounded by a deflated-byte budget, least-recently-used, and
 * that needs saying because it looked like it could grow without limit. Every
 * watched class the server loads is put here at load time, which on a large
 * SAP Commerce install measured about 4000 classes and roughly 20 MB deflated,
 * none of it ever evicted. Yet the only consumer is a superclass-salvage
 * reload, which reads the class it is right then reloading, so the entry it
 * needs is always the most recently touched one. Bounding to the most recent
 * classes therefore keeps every entry the salvage will actually ask for, and
 * the classes that fall off the end are the thousands loaded once at startup
 * and never edited. The cost of an eviction is the safe floor this cache
 * already declines to: a salvage that finds no entry refuses the whole save,
 * exactly as it does for a class that was never transformed. So a miss is
 * never wrong, only occasionally less generous, and the memory is now capped
 * instead of tracking the size of the codebase.
 */
public final class TransformedClassCache {

    /**
     * Deflated-byte ceiling. Chosen so a normal application caches entirely
     * (the measured average is a few KB per class, so this holds thousands)
     * while a very large install is bounded well under its uncapped footprint.
     */
    private static final long MAX_DEFLATED_BYTES = 8L * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static long deflatedTotal = 0;

    // Access-order LinkedHashMap: get() and put() move an entry to the most
    // recent end, and eviction takes from the least recent. All access is
    // under LOCK because eviction has to read and shrink the running total
    // atomically with the structural change.
    private static final LinkedHashMap<String, Entry> ENTRIES =
            new LinkedHashMap<>(256, 0.75f, true);

    private record Entry(byte[] deflated, int rawLength) {
    }

    private TransformedClassCache() {
    }

    /** Remember {@code bytes} as the latest emitted class file for {@code internalName}. */
    public static void put(String internalName, byte[] bytes) {
        if (internalName == null || bytes == null) return;
        byte[] deflated = deflate(bytes);
        Entry entry = new Entry(deflated, bytes.length);
        synchronized (LOCK) {
            Entry previous = ENTRIES.put(internalName, entry);
            deflatedTotal += deflated.length - (previous == null ? 0 : previous.deflated.length);
            evictWhileOverBudget(internalName);
        }
    }

    /** The latest emitted class file, or null when this class is not (or no longer) cached. */
    public static byte[] get(String internalName) {
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(internalName);   // also marks it most-recently-used
        }
        if (entry == null) return null;
        return inflate(entry.deflated, entry.rawLength);
    }

    public static int classCount() {
        synchronized (LOCK) {
            return ENTRIES.size();
        }
    }

    /** Total deflated payload in bytes, the number the memory measurement reads. */
    public static long deflatedBytes() {
        synchronized (LOCK) {
            return deflatedTotal;
        }
    }

    /**
     * Drop least-recently-used entries until under the byte budget, but never
     * the one just written: it is the most likely to be read next, and a
     * single class larger than the whole budget should stay rather than evict
     * itself into uselessness.
     */
    private static void evictWhileOverBudget(String justWritten) {
        var it = ENTRIES.entrySet().iterator();
        while (deflatedTotal > MAX_DEFLATED_BYTES && ENTRIES.size() > 1 && it.hasNext()) {
            Map.Entry<String, Entry> eldest = it.next();
            if (eldest.getKey().equals(justWritten)) continue;
            deflatedTotal -= eldest.getValue().deflated.length;
            it.remove();
        }
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
