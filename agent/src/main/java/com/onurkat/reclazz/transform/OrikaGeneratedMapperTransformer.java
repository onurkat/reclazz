/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Orika-generated callers are outside watched directories but call watched DTOs. */
public final class OrikaGeneratedMapperTransformer implements ClassFileTransformer {
    private final TransformContext context;
    public OrikaGeneratedMapperTransformer(TransformContext context) { this.context = context; }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                     ProtectionDomain domain, byte[] bytes) {
        if (name == null || !name.startsWith("ma/glasnost/orika/generated/")) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                        String signature, String[] exceptions) {
                return new CallSiteAdapter(super.visitMethod(access, method, descriptor, signature, exceptions),
                        context, name, loader);
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }
}
