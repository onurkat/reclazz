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

        // A removal from the end is the append's mirror: nothing renumbers,
        // so the ordinal argument that refuses every other removal does not
        // apply, and the surgery is the shrink instead of the grow.
        List<String> removedTail = EnumConstantChange.removedTailNames(loaded, newBytecode);
        if (!removedTail.isEmpty()) {
            EnumSurgery.Outcome outcome = EnumSurgery.removeFromEnd(loaded, removedTail);
            if (!outcome.applied()) {
                StatusReporter.warn("Enum " + className + " could not drop " + removedTail
                        + ": " + outcome.declinedBecause() + ". values() and valueOf() keep "
                        + "the old set until a restart. Everything else in this class reloaded.");
                com.onurkat.reclazz.agent.RestartLedger.note(className,
                        change.describe() + ", which could not be applied to this JVM");
                return false;
            }
            // The mapper caches still carry the removed constant: they would
            // keep serialising it and keep accepting its name.
            int mappers = JacksonEnumCaches.flush();
            EnumConstantChange.reportTailRemoved(className, removedTail, mappers);
            if (implementsHybrisEnumValue(loaded)) {
                StatusReporter.info("This is a SAP Commerce enumtype: the EnumerationValue "
                        + "item for " + removedTail + " still exists in the database and is "
                        + "yours to remove (ImpEx or HAC) when nothing references it.");
            }
            return true;
        }

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
            // One sentence, not two. Following "could not gain" with the
            // generic notice, which opens "gained value(s)", read as a
            // contradiction: the describe() there is about what the source
            // did, and next to a refusal it looked like the opposite of what
            // had just been said.
            StatusReporter.warn("Enum " + className + " could not gain " + names + ": "
                    + outcome.declinedBecause() + ". values() and valueOf() keep the old set "
                    + "until a restart. Everything else in this class reloaded.");
            com.onurkat.reclazz.agent.RestartLedger.note(className,
                    change.describe() + ", which could not be applied to this JVM");
            return false;
        }

        EnumCollectionHealer.armed();

        int tables = 0;
        if (instrumentation != null) {
            tables = EnumSurgery.growSwitchTables(
                    instrumentation.getAllLoadedClasses(), loaded, countConstants(loaded));
        }

        // Jackson keeps per-mapper enum caches sized to the old constant set;
        // measured to turn the new constant into a 500 on serialise and a 400
        // on deserialise until they are flushed. Zero mappers means no Spring
        // or no Jackson, and the report stays silent about a repair that had
        // nothing to repair.
        int mappers = JacksonEnumCaches.flush();

        EnumConstantChange.reportAppended(className, outcome.appended(), tables, mappers);

        // A static SAP Commerce enumtype compiles to a real Java enum, so the
        // append above made the JVM side whole; the platform side is not. The
        // persistence layer stores such a value as a reference to its
        // EnumerationValue item, and that row exists only after a system
        // update. Said here because this is the one place that knows both
        // facts; detected by interface name so a non-Hybris JVM never pays
        // for the question.
        if (implementsHybrisEnumValue(loaded)) {
            StatusReporter.info("This is a SAP Commerce enumtype: the platform can persist "
                    + names + " only after the matching EnumerationValue item exists. "
                    + "Run HAC -> Platform -> Update Running System (or import it via ImpEx) "
                    + "before using the value on a model.");
        }
        return true;
    }

    /** Walks the interface graph by name: no Hybris classes are ever loaded for this. */
    private static boolean implementsHybrisEnumValue(Class<?> type) {
        try {
            for (Class<?> iface : type.getInterfaces()) {
                if ("de.hybris.platform.core.HybrisEnumValue".equals(iface.getName())
                        || implementsHybrisEnumValue(iface)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // A class this JVM will not describe is not a Hybris enum.
        }
        return false;
    }

    private static int countConstants(Class<?> enumClass) {
        Object[] constants = enumClass.getEnumConstants();
        return constants == null ? 0 : constants.length;
    }
}
