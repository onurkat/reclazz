/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Checks saved DTO schemas before Orika returns an already-cached mapping strategy. */
public final class OrikaMapperCacheTransformer implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                     ProtectionDomain domain, byte[] bytes) {
        if (!"ma/glasnost/orika/impl/MapperFacadeImpl".equals(name)) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                        String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (!method.equals("resolveMappingStrategy") || !descriptor.equals(
                        "(Ljava/lang/Object;Ljava/lang/reflect/Type;Ljava/lang/reflect/Type;ZLma/glasnost/orika/MappingContext;)Lma/glasnost/orika/MappingStrategy;")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        for (int i = 0; i < 4; i++) super.visitVarInsn(Opcodes.ALOAD, i);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/onurkat/reclazz/bootstrap/OrikaBridge", "beforeMapping",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/reflect/Type;Ljava/lang/reflect/Type;)V", false);
                        changed[0] = true;
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : null;
    }
}
