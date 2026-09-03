/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An enum constant a reload added, which no JVM will make usable.
 *
 * <p>The constant is a static field holding an instance built in
 * {@code <clinit>}, and {@code values()} hands back a copy of a private array
 * built there too. The JVM runs {@code <clinit>} once, so on a stock JDK the
 * redefinition is refused outright and on JetBrains Runtime with enhanced class
 * redefinition it is accepted, adds the field, and leaves it null. Measured,
 * with the enum already initialised as it is in any running application:
 *
 * <pre>
 *   stock JDK 21             values()=[NEW, PAID]   valueOf("SHIPPED") throws
 *   JBR 25 + enhanced        values()=[NEW, PAID]   valueOf("SHIPPED") throws
 * </pre>
 *
 * <p>Conjuring the constant up is possible and was tried: allocate the
 * instance, enlarge the private array, clear the caches on {@code Class}. Then
 * {@code values()} and {@code valueOf} work, and every {@code EnumMap} and
 * {@code EnumSet} built before the reload throws
 * {@code ArrayIndexOutOfBoundsException} because it is sized by the old count,
 * while every switch compiled against the old constants silently takes its
 * default branch. Fixing that means rewriting objects on the heap and other
 * classes' static arrays. So the constant is not conjured, and the reload says
 * what happened instead.
 *
 * <p>This lives outside {@code StructuralReloader} because both engines need
 * it. On JetBrains Runtime that reloader is switched off and the class goes
 * straight to the JVM, which accepts the redefinition and reports a plain
 * success: the one configuration where the change looks most like it worked was
 * the one saying nothing.
 */
public final class EnumConstantChange {

    private EnumConstantChange() {
    }

    public record Change(List<String> added, List<String> removed, boolean reordered) {

        public String describe() {
            StringBuilder out = new StringBuilder();
            if (!added.isEmpty()) out.append(com.onurkat.reclazz.ui.Plural.word(added.size(),
                    "gained value ", "gained values ")).append(added);
            if (!removed.isEmpty()) {
                if (out.length() > 0) out.append(" and ");
                out.append(com.onurkat.reclazz.ui.Plural.word(removed.size(),
                        "lost value ", "lost values ")).append(removed);
            }
            if (reordered) {
                if (out.length() > 0) out.append(" and ");
                out.append("had its values reordered");
            }
            return out.toString();
        }
    }

    /**
     * Compare the constants the loaded enum has with the ones the new bytecode
     * declares.
     *
     * <p>Taken before the redefinition, like every other check of this kind:
     * enhanced redefinition adds the field, so afterwards the two sides agree
     * about a constant that still does not work.
     *
     * @return the change, or null when this is not an enum or nothing moved
     */
    public static Change check(Class<?> loaded, byte[] newBytecode) {
        if (loaded == null || newBytecode == null || !loaded.isEnum()) return null;

        Set<String> declared;
        try {
            declared = constantsIn(newBytecode);
        } catch (RuntimeException e) {
            return null;
        }
        if (declared.isEmpty()) return null;

        List<String> loadedConstants = liveConstants(loaded);
        if (loadedConstants == null) return null;

        List<String> added = new ArrayList<>(declared);
        added.removeAll(loadedConstants);

        List<String> removed = new ArrayList<>(loadedConstants);
        removed.removeAll(declared);

        // The same names in a different order is not nothing. Every ordinal
        // after the move changes, so the running enum and the source disagree
        // about which constant is which, and a set comparison would have called
        // that no change at all and said so by staying silent.
        boolean reordered = added.isEmpty() && removed.isEmpty()
                && !loadedConstants.equals(new ArrayList<>(declared));

        if (added.isEmpty() && removed.isEmpty() && !reordered) return null;
        return new Change(added, removed, reordered);
    }

    /**
     * Whether this change only puts constants on the end.
     *
     * <p>An appended constant takes the next ordinal and nothing that already
     * exists moves. One inserted in the middle, or removed, renumbers every
     * constant after it, and everything indexed by ordinal is then silently
     * wrong: the maps and sets already in memory, and every value a database
     * holds for an {@code @Enumerated} column, whose default is ORDINAL. No
     * repair reaches the database, so the whole thing is refused there.
     */
    public static boolean isAppendOnly(Class<?> loaded, byte[] newBytecode) {
        if (loaded == null || newBytecode == null || !loaded.isEnum()) return false;

        List<String> before = liveConstants(loaded);
        if (before == null) return false;
        List<String> after;
        try {
            after = new ArrayList<>(constantsIn(newBytecode));
        } catch (RuntimeException e) {
            return false;
        }
        if (after.size() <= before.size()) return false;
        return after.subList(0, before.size()).equals(before);
    }

    /** The constants this version adds on the end, in order. */
    public static List<String> appendedNames(Class<?> loaded, byte[] newBytecode) {
        if (!isAppendOnly(loaded, newBytecode)) return List.of();
        int existing = liveConstants(loaded).size();
        List<String> after = new ArrayList<>(constantsIn(newBytecode));
        return after.subList(existing, after.size());
    }

    /**
     * Whether this change only takes constants off the end.
     *
     * <p>The refusal of removal is about ordinals: taking a constant out of
     * the middle renumbers everything after it. Taking the LAST constant off
     * moves nothing, the survivors keep the ordinals they had, and every
     * ordinal-indexed structure stays right. It is the append's exact mirror,
     * and it is allowed for the mirror-image reason. At least one constant
     * has to survive: the JVM will not have an enum with none, and neither
     * will this.
     */
    public static boolean isTailRemovalOnly(Class<?> loaded, byte[] newBytecode) {
        if (loaded == null || newBytecode == null || !loaded.isEnum()) return false;

        List<String> before = liveConstants(loaded);
        if (before == null) return false;
        List<String> after;
        try {
            after = new ArrayList<>(constantsIn(newBytecode));
        } catch (RuntimeException e) {
            return false;
        }
        if (after.isEmpty() || after.size() >= before.size()) return false;
        return before.subList(0, after.size()).equals(after);
    }

    /** The constants this version drops from the end, in declaration order. */
    public static List<String> removedTailNames(Class<?> loaded, byte[] newBytecode) {
        if (!isTailRemovalOnly(loaded, newBytecode)) return List.of();
        List<String> before = liveConstants(loaded);
        int surviving = constantsIn(newBytecode).size();
        return new ArrayList<>(before.subList(surviving, before.size()));
    }

    /** The success report for a tail removal, with its honest edges. */
    public static void reportTailRemoved(String className, List<String> names, int mappers) {
        com.onurkat.reclazz.ui.StatusReporter.success("Enum " + className + " dropped "
                + names + " from the end: values() and valueOf() no longer include "
                + (names.size() == 1 ? "it" : "them") + ", and no ordinal moved."
                + (mappers > 0 ? " Jackson enum caches flushed on "
                        + com.onurkat.reclazz.ui.Plural.of(mappers, "ObjectMapper") + "." : ""));
        com.onurkat.reclazz.ui.StatusReporter.info("Objects and collections that already "
                + "hold the constant keep it, and a database row storing the name now "
                + "fails valueOf, which is what removal means.");
    }

    /** Check and say so. */
    public static void reportIfChanged(String className, Class<?> loaded, byte[] newBytecode) {
        report(className, check(loaded, newBytecode));
    }

    /** Said when the constants were actually added to the running JVM. */
    public static void reportAppended(String className, List<String> names, int switchTables,
                                      int jacksonMappers) {
        com.onurkat.reclazz.ui.StatusReporter.success("Enum " + className + " gained "
                + names + " without a restart: values(), valueOf() and new EnumMap/EnumSet see them"
                + (switchTables > 0
                        ? ", and " + com.onurkat.reclazz.ui.Plural.of(switchTables, "switch table")
                          + " grew, so existing switches send the new value to their "
                          + "default: the one written in the source, "
                          + "or, for a switch the compiler proved exhaustive, javac's own "
                          + "MatchException throw. Pattern switches ending in a type pattern "
                          + "match the new value directly"
                        : "")
                + (jacksonMappers > 0
                        ? ". " + com.onurkat.reclazz.ui.Plural.of(jacksonMappers, "Jackson ObjectMapper bean")
                          + com.onurkat.reclazz.ui.Plural.word(jacksonMappers, " had its enum ", " had their enum ")
                          + "caches flushed, so JSON serialises and deserialises the new value too"
                        : ""));

        // The JVM prints its own warning for this, and it ends with "Please
        // consider reporting this to the maintainers of
        // com.onurkat.reclazz.bootstrap.UnsafeAccess", which is us. Someone
        // reading that has been asked by their JVM to open an issue about a
        // deliberate choice. Answering it in the same breath costs one line
        // and saves them the trip.
        if (Runtime.version().feature() >= 24) {
            com.onurkat.reclazz.ui.StatusReporter.info("Java " + Runtime.version().feature()
                    + " prints a sun.misc.Unsafe deprecation warning for this and names Reclazz; "
                    + "it is expected. Writing a final field is what adding a constant needs "
                    + "and has no supported alternative. Starting the JVM with "
                    + "--sun-misc-unsafe-memory-access=allow silences it. On JDK 26, where that "
                    + "access is refused by default, Reclazz falls back to the JDK's own "
                    + "jdk.internal.misc.Unsafe, which the deprecation does not cover, so the "
                    + "flag is a way to keep this quiet rather than the only way to keep it "
                    + "working.");
        }
    }

    /** Said when the change moves ordinals, which cannot be applied. */
    public static void reportNotAppendOnly(String className, Change change) {
        com.onurkat.reclazz.ui.StatusReporter.warn("Enum " + className + " " + change.describe()
                + ". Only the end of an enum can change on a running JVM, added or removed: "
                + "anything else renumbers the constants after the change, and everything "
                + "indexed by ordinal is then wrong, including any @Enumerated column already in "
                + "your database. Restart to pick this up. Everything else in this class reloaded.");
        com.onurkat.reclazz.agent.RestartLedger.note(className,
                change.describe() + ", which renumbers ordinals and cannot be applied to a running JVM");
    }

    /** Say what a previously taken {@link #check} found. */
    public static void report(String className, Change change) {
        if (change == null) return;

        com.onurkat.reclazz.ui.StatusReporter.warn("Enum " + className + " " + change.describe()
                + ". Enum constants cannot be added to or removed from a running JVM: "
                + "values() and valueOf() keep the old set until a restart. Everything else "
                + "in this class reloaded.");
        com.onurkat.reclazz.agent.RestartLedger.note(className,
                change.describe() + ", which a running JVM cannot apply to an enum");
    }

    /**
     * The constants the enum actually has right now, which is not the same as
     * the fields it declares.
     *
     * <p>Once a constant has been appended, the loaded class still declares no
     * field for it on a stock JVM: the JVM cannot add one, so the value lives
     * in the store instead. Reading declared fields would therefore see the
     * same constant as missing on every later reload of that class and append
     * it a second time, and a third. {@code values()} is the answer that
     * already includes what was appended, so asking it is what makes this
     * idempotent.
     */
    private static List<String> liveConstants(Class<?> enumClass) {
        try {
            Object[] constants = enumClass.getEnumConstants();
            if (constants == null) return null;
            List<String> names = new ArrayList<>(constants.length);
            for (Object constant : constants) names.add(((Enum<?>) constant).name());
            return names;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The constant names a class file declares, in declaration order. */
    private static Set<String> constantsIn(byte[] bytecode) {
        Set<String> names = new LinkedHashSet<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                            String signature, Object value) {
                if ((access & Opcodes.ACC_ENUM) != 0) names.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return names;
    }
}
