/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.TransformedClassCache;
import com.onurkat.reclazz.ui.StatusReporter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Says the one thing about a changed compile-time constant no reload can fix.
 *
 * <p>javac copies a constant ({@code static final} with a ConstantValue) into
 * every use site at compile time; the field itself is read by nobody who was
 * compiled against it. So editing {@code MAX_RETRIES = 3} to {@code 5} and
 * reloading changes the field, and every OTHER class keeps behaving as if it
 * were 3, because 3 is literally what is in their bytecode. Nothing can repair
 * that from the class that changed: the dependents have to be rebuilt
 * themselves. The failure mode without this warning is a developer staring at
 * a constant that "did not take".
 *
 * <p>Saying only that much left the developer to work out which files those
 * were, which is a question the project can answer even though the bytecode
 * cannot. {@link ConstantDependents} takes the changed names from here and
 * looks for them in the watched modules' sources, so the warning is followed
 * by the list, or by the rebuild when Reclazz is the compiler.
 *
 * <p>The previous values come from the transformed-bytecode cache, which holds
 * what the transformer last emitted for the class. That cache has nothing for
 * the one class this matters most on. A class whose only members are constants
 * is never loaded at all: every use of it was inlined, so nothing at runtime
 * refers to it, so the JVM never reads it and the transformer never sees it.
 * Measured on a live Spring Boot server, editing exactly such a class produced
 * no warning at all, which is the failure this class exists to prevent,
 * arriving through the back door.
 *
 * <p>So what was last seen is also remembered here, on the way past. The first
 * save of a never-loaded constants class has nothing to compare against and
 * says so, naming the constants it declares rather than the ones that changed;
 * every save after that is an exact diff. Remembering is bounded, and a class
 * evicted from either memory gets no warning, never a wrong one.
 */
public final class ConstantChangeWarning {

    /**
     * The constants each class file last had, for classes the JVM never loads.
     * Bounded: a long session must not turn a diagnostic into a leak.
     */
    private static final int MAX_REMEMBERED = 512;

    private static final Map<String, Map<String, Object>> LAST_SEEN =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > MAX_REMEMBERED;
                }
            });

    private ConstantChangeWarning() {
    }

    /**
     * Compare ConstantValue attributes and warn on every changed one.
     *
     * @param loadedInJvm whether the JVM has this class; a constants-only
     *                    class it has never loaded is the case with no
     *                    baseline to compare against
     * @return the names to look for in other sources: the ones that changed,
     *         or every one this class declares when there is nothing to
     *         compare against yet
     */
    public static java.util.List<String> check(String internalName, String className,
                                               byte[] newBytecode, boolean loadedInJvm) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            if (newBytecode == null) return names;
            Map<String, Object> now = constantsIn(newBytecode);
            if (now.isEmpty()) return names;

            byte[] transformed = TransformedClassCache.get(internalName);
            Map<String, Object> before = transformed == null
                    ? LAST_SEEN.get(internalName)
                    : constantsIn(transformed);
            LAST_SEEN.put(internalName, now);

            if (before != null && !before.isEmpty()) {
                for (String line : changed(before, now)) {
                    StatusReporter.warn("Compile-time constant " + className + "." + line
                            + ". javac inlines constants at use sites, so classes compiled "
                            + "against the old value keep it until they are rebuilt; this "
                            + "class's own uses are current.");
                    names.add(line.substring(0, line.indexOf(' ')));
                }
                return names;
            }

            // No baseline. For a class the JVM holds, that means the cache was
            // evicted and silence is right. For one it has never loaded, it
            // means every use was inlined, which is exactly the class whose
            // edit reaches nothing, so the constants it declares are worth
            // looking for even without knowing which of them moved.
            if (!loadedInJvm) {
                names.addAll(now.keySet());
                StatusReporter.warn(className + " declares "
                        + com.onurkat.reclazz.ui.Plural.word(names.size(),
                                "compile-time constant ", "compile-time constants ")
                        + names + " and the JVM has never loaded it, which is what happens "
                        + "when every use of them was inlined. There is nothing to compare "
                        + "this save against, so rather than guess which of them moved, "
                        + "Reclazz looks for everything that reads any of them.");
            }
        } catch (Throwable neverBlocksAReload) {
            // A warning that cannot be computed is a warning not printed.
        }
        return names;
    }

    /** Each changed constant as "NAME changed from A to B", in field order. */
    static java.util.List<String> changedConstants(byte[] previous, byte[] current) {
        Map<String, Object> before = constantsIn(previous);
        if (before.isEmpty()) return new java.util.ArrayList<>();
        return changed(before, constantsIn(current));
    }

    private static java.util.List<String> changed(Map<String, Object> before,
                                                  Map<String, Object> after) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            Object now = after.get(entry.getKey());
            if (now == null || now.equals(entry.getValue())) continue;
            changed.add(entry.getKey() + " changed from " + describe(entry.getValue())
                    + " to " + describe(now));
        }
        return changed;
    }

    /** Test seam: a fresh session, so one test's class cannot answer another's. */
    static void forget() {
        LAST_SEEN.clear();
    }

    /** {@code static final} fields with a ConstantValue, name to value. */
    private static Map<String, Object> constantsIn(byte[] bytecode) {
        Map<String, Object> constants = new LinkedHashMap<>();
        if (bytecode == null) return constants;
        new org.objectweb.asm.ClassReader(bytecode).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(
                            int access, String name, String descriptor,
                            String signature, Object value) {
                        int statFinal = org.objectweb.asm.Opcodes.ACC_STATIC
                                | org.objectweb.asm.Opcodes.ACC_FINAL;
                        if (value != null && (access & statFinal) == statFinal) {
                            constants.put(name, value);
                        }
                        return null;
                    }
                }, org.objectweb.asm.ClassReader.SKIP_CODE);
        return constants;
    }

    private static String describe(Object value) {
        if (value instanceof String s) {
            String shown = s.length() > 40 ? s.substring(0, 37) + "..." : s;
            return '"' + shown + '"';
        }
        return String.valueOf(value);
    }
}
