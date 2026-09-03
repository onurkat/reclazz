/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.ui.StatusReporter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The classes {@code excludeClasses} left alone, and what that means later.
 *
 * <p>Excluding is not silent, because a class that is not instrumented behaves
 * differently in a way nobody will connect to a setting they wrote once: adding
 * a method to it does nothing and the reason is three weeks old. It is also not
 * loud, because an exclusion is usually a package and a package is thousands of
 * classes. Said once, with a count that is only as fresh as the moment it was
 * printed, which is the honest thing a load-time hook can say.
 *
 * <p>Remembered as well, so the refusal a structural reload gives later names
 * this rather than guessing at one of the other reasons a class can be
 * uninstrumented.
 */
public final class ExcludedClasses {

    private static final Set<String> excluded = ConcurrentHashMap.newKeySet();

    private static final AtomicBoolean announced = new AtomicBoolean(false);

    private ExcludedClasses() {
    }

    /** Record a class the transform was told to leave alone. */
    public static void note(String className) {
        if (className == null) return;
        excluded.add(className.replace('/', '.'));

        if (announced.compareAndSet(false, true)) {
            StatusReporter.info("excludeClasses matched " + className.replace('/', '.')
                    + " and it is being left uninstrumented, along with anything else the "
                    + "patterns match. Those classes still reload method body changes, which "
                    + "is the JVM's own redefinition and needs nothing from Reclazz; adding or "
                    + "removing members on them does not.");
        }
    }

    /** Whether this class was left alone because it was asked for by name. */
    public static boolean wasExcluded(String className) {
        return className != null && excluded.contains(className.replace('/', '.'));
    }

    /** How many classes the patterns have matched so far. */
    public static int count() {
        return excluded.size();
    }

    /** Tests need a clean session; nothing in the agent calls this. */
    static void resetForTests() {
        excluded.clear();
        announced.set(false);
    }
}
