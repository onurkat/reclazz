/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Keeps Orika's property resolver in charge, then adds saved bean accessor metadata. */
public final class OrikaPropertyTransformer implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                     ProtectionDomain domain, byte[] bytes) {
        if (!"ma/glasnost/orika/property/PropertyResolver".equals(name)) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                        String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (!method.equals("getProperties") || !descriptor.equals("(Ljava/lang/reflect/Type;)Ljava/util/Map;")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/onurkat/reclazz/bootstrap/OrikaBridge", "properties",
                                    "(Ljava/util/Map;Ljava/lang/reflect/Type;Ljava/lang/Object;)Ljava/util/Map;", false);
                            changed[0] = true;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : null;
    }
}
