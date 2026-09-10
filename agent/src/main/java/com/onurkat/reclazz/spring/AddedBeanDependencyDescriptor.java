/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.lang.invoke.*;
import org.objectweb.asm.*;

/** The outer Spring resolver must not replace hidden metadata's local name discoverer. */
final class AddedBeanDependencyDescriptor {
    private AddedBeanDependencyDescriptor() { }

    static MethodHandle constructor(MethodHandles.Lookup lookup, Class<?> parameterType) throws ReflectiveOperationException {
        if (lookup == null) throw new IllegalStateException("no captured lookup for factory argument metadata");
        String name = Type.getInternalName(lookup.lookupClass()) + "$$ReclazzDependency";
        String parent = "org/springframework/beans/factory/config/DependencyDescriptor";
        String signature = "(Lorg/springframework/core/MethodParameter;Z)V";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, null, parent, null);
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", signature, null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ALOAD, 1); ctor.visitVarInsn(Opcodes.ILOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", signature, false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "initParameterNameDiscovery",
                "(Lorg/springframework/core/ParameterNameDiscoverer;)V", null, null);
        init.visitCode(); init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd(); writer.visitEnd();
        MethodHandles.Lookup hidden = lookup.defineHiddenClass(writer.toByteArray(), true);
        return hidden.findConstructor(hidden.lookupClass(), MethodType.methodType(void.class, parameterType, boolean.class));
    }
}
