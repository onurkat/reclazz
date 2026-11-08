/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * What one reload touched, gathered for the one line that says so.
 *
 * <p>A reload of a Spring bean used to be a swap line followed by one line
 * per framework step that did something, in the order the steps happened to
 * run. Together they answered the question a developer has after every save,
 * "did it reload, and what did it touch", but only once collected, and by the
 * next save they had scrolled. So each step now notes its effect here in a few
 * words, and the reload line carries them: {@code Reloaded OrderService (12ms):
 * bean orderService re-created, mappings re-scanned, caches evicted}. The
 * step's own sentence is still printed under verbose.
 *
 * <p>The queue serialises reloads. A batch retains a record per class across
 * structural work, native callbacks and framework work; selecting a sibling
 * never attributes its effects to the previous class. A note outside an active
 * class is dropped, and batch end discards every retained record.
 */
public final class ReloadEffects {

    private static final class Effects {
        final List<String> notes = new ArrayList<>();
        int newRestarts;
        int selectedAt;
    }
    private static Effects current;
    private static int batchDepth;
    private static java.util.Map<String, Effects> batch;

    private ReloadEffects() { }

    /** Start independent effect records for one serialized reload batch. */
    public static synchronized void beginBatch() {
        if (batchDepth++ == 0) {
            current = null;
            batch = new java.util.LinkedHashMap<>();
        }
    }

    /** Clear even records belonging to a failed or abandoned class. */
    public static synchronized void endBatch() {
        if (batchDepth == 0 || --batchDepth > 0) return;
        current = null;
        batch = null;
    }

    private static void account() {
        if (current != null) {
            current.newRestarts += Math.max(0, RestartLedger.size() - current.selectedAt);
            current.selectedAt = RestartLedger.size();
        }
    }

    /** A reload is starting; forget what the previous one left, if anything. */
    public static synchronized void begin() {
        current = new Effects();
        current.selectedAt = RestartLedger.size();
    }

    /** Resume this class's structural/native/framework effects inside a batch. */
    public static synchronized void begin(String owner) {
        account();
        current = batch == null ? new Effects() : batch.computeIfAbsent(owner, ignored -> new Effects());
        current.selectedAt = RestartLedger.size();
    }

    /** Detach without discarding the class record while native redefinition waits. */
    public static synchronized void suspend() {
        account();
        current = null;
    }

    /** Attribute a deferred native callback to its own class, then restore the caller. */
    public static void forClass(String owner, Runnable work) {
        Effects previous;
        synchronized (ReloadEffects.class) {
            if (batch == null) { previous = null; }
            else {
                account();
                previous = current;
                current = batch.computeIfAbsent(owner, ignored -> new Effects());
                current.selectedAt = RestartLedger.size();
            }
        }
        try { work.run(); }
        finally {
            synchronized (ReloadEffects.class) {
                if (batch != null) {
                    account();
                    current = previous;
                    if (current != null) current.selectedAt = RestartLedger.size();
                }
            }
        }
    }

    /** A note outside a reload is intentionally ignored. */
    public static synchronized void note(String effect) {
        if (current == null || effect == null || effect.isBlank()) return;
        if (!current.notes.contains(effect)) current.notes.add(effect);
    }

    /** Publish this class's tail, closing its active collection. */
    public static synchronized String end() {
        if (current == null) return "";
        account();
        String result = digest(current.notes, current.newRestarts);
        current = null;
        return result;
    }

    /** The tail without the leading separator; package-visible for its test. */
    static String digest(List<String> effects, int newRestarts) {
        StringBuilder text = new StringBuilder();
        if (!effects.isEmpty()) text.append(String.join(", ", effects));
        if (newRestarts > 0) {
            if (text.length() > 0) text.append("; ");
            text.append(Plural.of(newRestarts, "thing")).append(" now ")
                    .append(newRestarts == 1 ? "waits" : "wait")
                    .append(" on a restart, ask PENDING");
        }
        return text.toString();
    }
}
