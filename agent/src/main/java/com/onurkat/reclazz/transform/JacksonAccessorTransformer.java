/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Equal-stack-effect reflection rewrites confined to Jackson databind. */
public final class JacksonAccessorTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/JacksonBridge";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                            ProtectionDomain domain, byte[] bytes) {
        if (name == null || !name.startsWith("com/fasterxml/jackson/databind/")) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            boolean[] changed = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                           String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String descriptor, boolean isInterface) {
                            String replacement = null;
                            if (opcode == Opcodes.INVOKEVIRTUAL) {
                                if (owner.equals("java/lang/Class") && method.equals("getDeclaredMethods")
                                        && descriptor.equals("()[Ljava/lang/reflect/Method;"))
                                    replacement = "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;";
                                if (owner.equals("java/lang/reflect/Method")) {
                                    if (method.equals("invoke") && descriptor.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"))
                                        replacement = "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
                                    if (method.equals("getDeclaringClass") && descriptor.equals("()Ljava/lang/Class;"))
                                        replacement = "(Ljava/lang/reflect/Method;)Ljava/lang/Class;";
                                }
                            }
                            if (replacement == null) super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                            else {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, method, replacement, false);
                            }
                        }
                    };
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : null;
        } catch (Throwable failure) {
            StatusReporter.warn("Jackson getter hook could not transform " + name + ": " + failure.getClass().getSimpleName());
            return null;
        }
    }
}
