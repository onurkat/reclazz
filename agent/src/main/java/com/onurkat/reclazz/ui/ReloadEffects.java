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
 * <p>One reload at a time: the queue serialises them (ReloadsAreSerialisedTest),
 * so a single current list is enough, and a note made outside a reload is
 * simply dropped rather than attributed to the next one.
 */
public final class ReloadEffects {

    private static final List<String> current = new ArrayList<>();
    private static boolean open;
    private static int restartsBefore;

    private ReloadEffects() {
    }

    /** A reload is starting; forget what the previous one left, if anything. */
    public static synchronized void begin() {
        current.clear();
        open = true;
        restartsBefore = RestartLedger.size();
    }

    /**
     * @param effect a few words in the developer's terms, such as
     *               {@code bean orderService re-created}
     */
    public static synchronized void note(String effect) {
        if (!open || effect == null || effect.isBlank()) return;
        if (!current.contains(effect)) current.add(effect);
    }

    /**
     * What the reload touched, as the tail of its line, and close the
     * collection. Empty when nothing was noted and nothing new waits on a
     * restart, so a reload of a plain class reads as it always did.
     */
    public static synchronized String end() {
        List<String> effects = new ArrayList<>(current);
        int newRestarts = Math.max(0, RestartLedger.size() - restartsBefore);
        current.clear();
        open = false;
        return digest(effects, newRestarts);
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
