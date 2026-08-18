/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.EnumCollectionHealer;
import com.onurkat.reclazz.bootstrap.EnumSurgery;
import com.onurkat.reclazz.transform.EnumCollectionTransformer;
import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.Instrumentation;
import java.util.List;

/**
 * Adds enum constants to a running JVM, or declines and says why.
 *
 * <p>This is the piece that decides. The measurements it rests on were taken
 * on JDK 21 and JDK 25, on a stock JVM and on JetBrains Runtime, before any of
 * it was written:
 *
 * <pre>
 *   values()            = [NEW, PAID, SHIPPED]
 *   valueOf(SHIPPED)    = SHIPPED
 *   switch(SHIPPED)     = default branch          (was ArrayIndexOutOfBounds)
 *   switch(PAID)        = unchanged
 *   old EnumMap.put     = works                   (was ArrayIndexOutOfBounds)
 *   old EnumSet.add     = works                   (was ArrayIndexOutOfBounds)
 * </pre>
 *
 * <p>Three things have to be true together, and each one is checked before
 * anything is written.
 *
 * <p>The change must be an append. A constant inserted in the middle or removed
 * renumbers the ones after it, and nothing in memory or in a database survives
 * that; it is refused with its own message.
 *
 * <p>The JDK must have the shapes this reaches into. They are not a contract,
 * so a JDK that moves one of them makes this decline, and the reload falls back
 * to the message it printed before any of this existed. It never half-applies.
 *
 * <p>Nothing is installed until it is needed. The transform that repairs
 * collections built before the append goes in on the first successful append
 * and not before, so an application that never reloads an enum runs untouched
 * {@code java.util} code.
 */
public final class EnumConstantAppender {

    private EnumConstantAppender() {
    }

    /**
     * Apply the enum change if it can be applied, and report either way.
     *
     * @param change the diff taken before the redefinition
     * @return true when the constants are live in the JVM
     */
    public static boolean applyOrExplain(String className, Class<?> loaded, byte[] newBytecode,
                                          EnumConstantChange.Change change,
                                          Instrumentation instrumentation) {
        if (change == null) return false;

        if (!EnumConstantChange.isAppendOnly(loaded, newBytecode)) {
            EnumConstantChange.reportNotAppendOnly(className, change);
            return false;
        }

        List<String> names = EnumConstantChange.appendedNames(loaded, newBytecode);
        if (names.isEmpty()) {
            EnumConstantChange.report(className, change);
            return false;
        }

        // The collections have to be able to catch up before the constant
        // exists, or the first use of it races the transform.
        String collectionsProblem = EnumCollectionTransformer.install(instrumentation);
        if (collectionsProblem != null) {
            StatusReporter.warn("Enum " + className + " could gain " + names
                    + ", but the EnumMap/EnumSet repair could not be installed ("
                    + collectionsProblem + "), and without it a map or set built before "
                    + "the reload would throw on the new value.");
            EnumConstantChange.report(className, change);
            return false;
        }

        EnumSurgery.Outcome outcome = EnumSurgery.append(loaded, names);
        if (!outcome.applied()) {
            StatusReporter.warn("Enum " + className + " could not gain " + names + ": "
                    + outcome.declinedBecause() + ".");
            EnumConstantChange.report(className, change);
            return false;
        }

        EnumCollectionHealer.armed();

        int tables = 0;
        if (instrumentation != null) {
            tables = EnumSurgery.growSwitchTables(
                    instrumentation.getAllLoadedClasses(), loaded, countConstants(loaded));
        }

        EnumConstantChange.reportAppended(className, outcome.appended(), tables);
        return true;
    }

    private static int countConstants(Class<?> enumClass) {
        Object[] constants = enumClass.getEnumConstants();
        return constants == null ? 0 : constants.length;
    }
}
