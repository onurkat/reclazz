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
 * were 3, because 3 is literally what is in their bytecode. No tool can
 * repair that from the class that changed; the classes that inlined the value
 * have to be rebuilt themselves, and most build tools do exactly that when
 * asked to compile (javac tracks constant dependencies), at which point the
 * ordinary reload picks them up. The failure mode without this warning is a
 * developer staring at a constant that "did not take".
 *
 * <p>The previous values come from the transformed-bytecode cache, which
 * holds what the transformer last emitted for the class; a class evicted from
 * that cache simply gets no warning, never a wrong one.
 */
public final class ConstantChangeWarning {

    private ConstantChangeWarning() {
    }

    /** Compare ConstantValue attributes and warn on every changed one. */
    public static void check(String internalName, String className, byte[] newBytecode) {
        try {
            byte[] previous = TransformedClassCache.get(internalName);
            if (previous == null || newBytecode == null) return;
            for (String line : changedConstants(previous, newBytecode)) {
                StatusReporter.warn("Compile-time constant " + className + "." + line
                        + ". javac inlines constants at use sites, so classes compiled "
                        + "against the old value keep it until they are rebuilt; this "
                        + "class's own uses are current.");
            }
        } catch (Throwable neverBlocksAReload) {
            // A warning that cannot be computed is a warning not printed.
        }
    }

    /** Each changed constant as "NAME changed from A to B", in field order. */
    static java.util.List<String> changedConstants(byte[] previous, byte[] current) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        Map<String, Object> before = constantsIn(previous);
        if (before.isEmpty()) return changed;
        Map<String, Object> after = constantsIn(current);
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            Object now = after.get(entry.getKey());
            if (now == null || now.equals(entry.getValue())) continue;
            changed.add(entry.getKey() + " changed from " + describe(entry.getValue())
                    + " to " + describe(now));
        }
        return changed;
    }

    /** {@code static final} fields with a ConstantValue, name to value. */
    private static Map<String, Object> constantsIn(byte[] bytecode) {
        Map<String, Object> constants = new LinkedHashMap<>();
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
