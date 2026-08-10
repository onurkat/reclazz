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
 * Rewrites field access (GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC) on watched classes
 * to invokedynamic instructions dispatched through ReclazzBootstrap.
 */
public class FieldAccessAdapter extends MethodVisitor implements Opcodes {

    private static final String BOOTSTRAP_CLASS = "com/onurkat/reclazz/bootstrap/ReclazzBootstrap";

    private final TransformContext context;
    private final String currentClass;
    private final java.util.Set<String> declaredFinalFieldKeys;

    public FieldAccessAdapter(MethodVisitor mv, TransformContext context, String currentClass) {
        this(mv, context, currentClass, java.util.Collections.emptySet());
    }

    public FieldAccessAdapter(MethodVisitor mv, TransformContext context, String currentClass,
                               java.util.Set<String> declaredFinalFieldKeys) {
        super(ASM9, mv);
        this.context = context;
        this.currentClass = currentClass;
        this.declaredFinalFieldKeys = declaredFinalFieldKeys;
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        // Skip if not a watched class
        if (!context.isWatched(owner)) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            return;
        }

        // Skip excluded fields (access flags unavailable in visitFieldInsn;
        // passing 0 is safe because all critical synthetic fields are caught by name patterns)
        if (TransformExclusions.shouldSkipField(owner, name, descriptor, 0)) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            return;
        }

        // Skip writes to declared final fields on the current class. Final fields
        // cannot be re-pointed via MethodHandles.findSetter, and constructors/clinit
        // need direct PUTFIELD/PUTSTATIC to initialize them. The declared-final set
        // is populated by the parent class visitor before methods are visited.
        if ((opcode == PUTFIELD || opcode == PUTSTATIC)
                && owner.equals(currentClass)
                && declaredFinalFieldKeys.contains(name + ":" + descriptor)) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            return;
        }

        Type fieldType = Type.getType(descriptor);

        switch (opcode) {
            case GETFIELD -> {
                // Stack: objectref -> value
                // invokedynamic type: (Owner)FieldType
                String indyDesc = "(L" + owner + ";)" + descriptor;
                Handle bsm = new Handle(H_INVOKESTATIC, BOOTSTRAP_CLASS,
                        "bootstrapFieldGet",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                                "Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                        false);
                super.visitInvokeDynamicInsn(name, indyDesc, bsm, owner);
            }
            case PUTFIELD -> {
                // Stack: objectref, value -> (void)
                // invokedynamic type: (Owner, FieldType)void
                String indyDesc = "(L" + owner + ";" + descriptor + ")V";
                Handle bsm = new Handle(H_INVOKESTATIC, BOOTSTRAP_CLASS,
                        "bootstrapFieldSet",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                                "Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                        false);
                super.visitInvokeDynamicInsn(name, indyDesc, bsm, owner);
            }
            case GETSTATIC -> {
                // Stack: -> value
                // invokedynamic type: ()FieldType
                String indyDesc = "()" + descriptor;
                Handle bsm = new Handle(H_INVOKESTATIC, BOOTSTRAP_CLASS,
                        "bootstrapStaticFieldGet",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                                "Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                        false);
                super.visitInvokeDynamicInsn(name, indyDesc, bsm, owner);
            }
            case PUTSTATIC -> {
                // Stack: value -> (void)
                // invokedynamic type: (FieldType)void
                String indyDesc = "(" + descriptor + ")V";
                Handle bsm = new Handle(H_INVOKESTATIC, BOOTSTRAP_CLASS,
                        "bootstrapStaticFieldSet",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                                "Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                        false);
                super.visitInvokeDynamicInsn(name, indyDesc, bsm, owner);
            }
            default -> super.visitFieldInsn(opcode, owner, name, descriptor);
        }
    }
}
