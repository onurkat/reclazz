/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Retains explicit default-mapping intent; an explicit field list must stay explicit. */
public final class OrikaMappingPolicyTransformer implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                     ProtectionDomain domain, byte[] bytes) {
        boolean mapCopy = "ma/glasnost/orika/metadata/ClassMap".equals(name);
        if (!mapCopy && !"ma/glasnost/orika/metadata/ClassMapBuilder".equals(name)) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                        String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (method.equals("byDefault") && descriptor.equals(
                        "(Lma/glasnost/orika/metadata/MappingDirection;[Lma/glasnost/orika/DefaultFieldMapper;)Lma/glasnost/orika/metadata/ClassMapBuilder;")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            for (int i = 0; i < 3; i++) super.visitVarInsn(Opcodes.ALOAD, i);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/onurkat/reclazz/bootstrap/OrikaBridge", "defaults",
                                    "(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)V", false);
                            changed[0] = true;
                        }
                    };
                }
                if ((!mapCopy && method.equals("toClassMap") && descriptor.equals("()Lma/glasnost/orika/metadata/ClassMap;"))
                        || (mapCopy && (method.equals("copy") || method.equals("copyWithUsedMappers"))
                        && descriptor.equals("(Ljava/util/Set;)Lma/glasnost/orika/metadata/ClassMap;"))) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                super.visitInsn(Opcodes.DUP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/onurkat/reclazz/bootstrap/OrikaBridge", mapCopy ? "copiedPlan" : "plan",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                changed[0] = true;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : null;
    }
}
