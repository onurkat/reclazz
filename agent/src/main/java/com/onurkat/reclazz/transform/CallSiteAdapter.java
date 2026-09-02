/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rewrites cross-class method invocations on watched classes to invokedynamic,
 * so callers automatically dispatch to the latest version of called methods.
 *
 * Applied within method bodies of watched classes.
 */
public class CallSiteAdapter extends MethodVisitor implements Opcodes {

    private static final String BOOTSTRAP_CLASS = "com/onurkat/reclazz/bootstrap/ReclazzBootstrap";

    private final TransformContext context;
    private final String currentClass;

    public CallSiteAdapter(MethodVisitor mv, TransformContext context, String currentClass) {
        super(ASM9, mv);
        this.context = context;
        this.currentClass = currentClass;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // INVOKESPECIAL: constructors stay as-is. For super calls and private
        // method calls on a watched class, redirect to the renamed copy
        // directly — calling the trampoline would do virtual dispatch and
        // loop back into the override (e.g., super.greet() calling the
        // child's overridden greet() forever).
        if (opcode == INVOKESPECIAL) {
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            // The parent must actually have the renamed body, not merely be
            // in a watched directory. A class loaded before the agent could
            // reach it is watched and untouched, and so is one whose transform
            // failed; rewriting into either names a method that is not there,
            // and nothing says so until the line runs:
            //
            //   NoSuchMethodError: GeneratedBadge.__reclazz$v0$createItem$2c9e...
            //       at Badge.__reclazz$v0$createItem$2c9e...(Badge.java:19)
            //
            // Reported from a SAP Commerce project, where every jalo item
            // class extends a generated one that is loaded while the type
            // system is built. The class being transformed right now counts as
            // instrumented: it is about to be, and its own private calls are
            // rewritten in the same pass.
            boolean safeToRewrite = owner.equals(currentClass)
                    || (context.isWatched(owner) && !context.isLoadedUninstrumented(owner));
            if (safeToRewrite && !TransformExclusions.shouldSkipCallTarget(owner)) {
                String descHash = descHash(descriptor);
                String renamedName = "__reclazz$v0$" + name + "$" + descHash;
                super.visitMethodInsn(opcode, owner, renamedName, descriptor, isInterface);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            return;
        }

        // Skip interface call sites: interfaces are not transformed by
        // ReclazzTransformer (they cannot host PRIVATE SYNTHETIC fields), so
        // they have no renamed __reclazz$v0$ method to dispatch to. Calls go
        // through normal Java virtual dispatch — and the actual implementation
        // class still gets trampolined separately.
        if (isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            return;
        }

        // Skip JDK/platform classes
        if (TransformExclusions.shouldSkipCallTarget(owner)) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            return;
        }

        // Skip non-watched targets
        if (!context.isWatched(owner)) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            return;
        }

        // Skip constructors (handled separately)
        if ("<init>".equals(name) || "<clinit>".equals(name)) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            return;
        }

        String descHash = descHash(descriptor);

        if (opcode == INVOKESTATIC) {
            // Static method: invokedynamic with same descriptor
            Handle bsm = new Handle(H_INVOKESTATIC, BOOTSTRAP_CLASS,
                    "bootstrapStaticMethod",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                            "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)" +
                            "Ljava/lang/invoke/CallSite;",
                    false);
            super.visitInvokeDynamicInsn(name, descriptor, bsm, owner, descHash);
        } else {
            // Instance method (INVOKEVIRTUAL / INVOKEINTERFACE)
            // Prepend receiver type: (Owner, args...) -> retType
            String indyDesc = "(L" + owner + ";" + descriptor.substring(1);
            Handle bsm = new Handle(H_INVOKESTATIC, BOOTSTRAP_CLASS,
                    "bootstrapMethod",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                            "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)" +
                            "Ljava/lang/invoke/CallSite;",
                    false);
            super.visitInvokeDynamicInsn(name, indyDesc, bsm, owner, descHash);
        }
    }

    public static String descHash(String descriptor) {
        // FNV-1a 64-bit hash for collision resistance across overloaded methods.
        // String.hashCode() (32-bit) has too high a collision risk for this use case.
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < descriptor.length(); i++) {
            h ^= descriptor.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h & 0x7FFFFFFFFFFFFFFFL);
    }
}
