/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Puts the monitor back around a synchronized body that has moved.
 *
 * <p>A structural reload copies a method's bytecode into the companion, which
 * holds it as a static method. {@code ACC_SYNCHRONIZED} cannot travel with it:
 * on a static method that flag takes the companion class's own monitor, not the
 * object's, so the copied body took no useful monitor at all and the methods
 * stopped excluding each other. Measured, two threads through a method that
 * holds for 300ms:
 *
 * <pre>
 *   before the structural reload   618 ms   they exclude
 *   after it                       305 ms   they do not
 * </pre>
 *
 * <p>The repair is the one the JVM does for a {@code synchronized} block, and
 * the reason it was written down rather than written is that getting it subtly
 * wrong is expensive: an unbalanced exit is a VerifyError at best and a lock
 * held forever at worst. So it is deliberately the smallest shape that is
 * correct.
 *
 * <p>No new locals. The monitor is pushed again at each exit rather than kept
 * in a slot: for an instance method that is the receiver, which the companion
 * takes as parameter zero and which javac never reassigns, and for a static
 * method it is the declaring class, which is what {@code static synchronized}
 * locks. The handler needs no slot either, since the throwable is already on
 * the stack and stays under the monitor while it is released.
 */
final class SynchronizedBodyAdapter extends MethodVisitor {

    private final boolean originalWasStatic;

    private final String ownerInternalName;

    private final Label bodyStart = new Label();

    private final Label bodyEnd = new Label();

    private final Label releaseAndRethrow = new Label();

    /**
     * @param originalWasStatic whether the method was static before it moved,
     *                          which decides what its monitor is
     * @param ownerInternalName the class that declared it, whose Class object
     *                          is the monitor for a static method
     */
    SynchronizedBodyAdapter(MethodVisitor delegate, boolean originalWasStatic,
                            String ownerInternalName) {
        super(Opcodes.ASM9, delegate);
        this.originalWasStatic = originalWasStatic;
        this.ownerInternalName = ownerInternalName;
    }

    @Override
    public void visitCode() {
        super.visitCode();
        // Declared before either label is visited, which is what ASM requires.
        super.visitTryCatchBlock(bodyStart, bodyEnd, releaseAndRethrow, null);
        pushMonitor();
        super.visitInsn(Opcodes.MONITORENTER);
        super.visitLabel(bodyStart);
    }

    @Override
    public void visitInsn(int opcode) {
        // Every ordinary way out releases it. The exceptional ways go through
        // the handler below, which is what the try-catch range is for.
        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
            pushMonitor();
            super.visitInsn(Opcodes.MONITOREXIT);
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitLabel(bodyEnd);
        super.visitLabel(releaseAndRethrow);
        // The throwable is on the stack and stays there: the monitor goes on
        // top of it, monitorexit takes it back off, and what is left is the
        // throwable this rethrows.
        pushMonitor();
        super.visitInsn(Opcodes.MONITOREXIT);
        super.visitInsn(Opcodes.ATHROW);
        super.visitMaxs(maxStack + 2, maxLocals);
    }

    private void pushMonitor() {
        if (originalWasStatic) {
            super.visitLdcInsn(Type.getObjectType(ownerInternalName));
        } else {
            super.visitVarInsn(Opcodes.ALOAD, 0);
        }
    }
}
