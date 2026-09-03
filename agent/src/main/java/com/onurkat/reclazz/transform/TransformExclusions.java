/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

/**
 * Determines which methods, fields, and bootstrap handles should be excluded
 * from Reclazz transformation to avoid breaking JVM invariants.
 */
public final class TransformExclusions {

    private TransformExclusions() {}

    /**
     * Check if a method should be skipped during trampoline transformation.
     */
    public static boolean shouldSkipMethod(String owner, String name, String desc, int access) {
        // Native methods cannot be trampolined
        if ((access & Opcodes.ACC_NATIVE) != 0) return true;

        // Abstract methods have no body
        if ((access & Opcodes.ACC_ABSTRACT) != 0) return true;

        // Static initializer — keep as-is (only inject __reclazz$lookup init)
        if ("<clinit>".equals(name)) return true;

        // Enum synthetic methods
        if ("values".equals(name) && desc.startsWith("()")) return true;
        if ("valueOf".equals(name) && desc.startsWith("(Ljava/lang/String;)")) return true;

        // Synthetic access bridges (inner class access)
        if (name.startsWith("access$")) return true;

        // Lambda body methods: javac compiles lambdas using LambdaMetafactory
        // with a CONSTANT_MethodHandle pointing directly at lambda$N synthetic
        // methods. If we trampoline these (rename + replace with invokedynamic),
        // the metafactory's link-time MH resolution still finds the trampoline,
        // but the resulting target dispatches via our invokedynamic which
        // currently produces wrong results in some configurations. Safer to
        // leave lambda body methods untouched. They're rarely the hot-reload
        // target — users edit their own methods, and the lambda body is reached
        // from there.
        if (name.startsWith("lambda$")) return true;

        return false;
    }

    /**
     * Check if a field should be excluded from invokedynamic rewriting.
     */
    public static boolean shouldSkipField(String owner, String name, String desc, int access) {
        // Our injected fields
        if (com.onurkat.reclazz.bootstrap.InjectedNames.isInjected(name)) return true;

        // Inner class outer reference
        if (name.startsWith("this$")) return true;

        // Enum values array
        if ("$VALUES".equals(name)) return true;

        // Synthetic fields (compiler-generated)
        if ((access & Opcodes.ACC_SYNTHETIC) != 0 && name.startsWith("$")) return true;

        return false;
    }

    /**
     * Check if a bootstrap method handle is a LambdaMetafactory bootstrap.
     * These should NOT be rewritten — they're JVM's own lambda plumbing.
     */
    public static boolean isLambdaBootstrap(Handle bsm) {
        if (bsm == null) return false;
        return "java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner());
    }

    /**
     * Check if a method call target should be skipped for cross-class rewriting.
     */
    public static boolean shouldSkipCallTarget(String owner) {
        // JDK classes
        if (owner.startsWith("java/")) return true;
        if (owner.startsWith("javax/")) return true;
        if (owner.startsWith("jdk/")) return true;
        if (owner.startsWith("sun/")) return true;
        if (owner.startsWith("com/sun/")) return true;

        return false;
    }
}
