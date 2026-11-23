/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * R35: Gson discovers a class's fields with {@code Class.getDeclaredFields()} and reads and writes them with
 * {@code Field.get/set} (ReflectiveTypeAdapterFactory). Inside Gson only, those calls go through the same metadata bridge
 * Jackson uses, so a field a reload added (stored outside the JVM class on a stock JVM) is serialized and deserialized.
 * Live Gson instances are registered by CacheOwnerTransformer, so their type-adapter caches can be cleared after such a reload.
 * Equal stack effect, confined to {@code com/google/gson/}.
 */
public final class GsonAccessorTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/JacksonBridge";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if (name == null || !name.startsWith("com/google/gson/")) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            boolean[] changed = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, desc, signature, exceptions)) {
                        @Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean isInterface) {
                            String replacement = null;
                            if (opcode == Opcodes.INVOKEVIRTUAL) {
                                if (owner.equals("java/lang/Class") && called.equals("getDeclaredFields")
                                        && descriptor.equals("()[Ljava/lang/reflect/Field;"))
                                    replacement = "(Ljava/lang/Class;)[Ljava/lang/reflect/Field;";
                                if (owner.equals("java/lang/reflect/Field")) {
                                    if (called.equals("get") && descriptor.equals("(Ljava/lang/Object;)Ljava/lang/Object;"))
                                        replacement = "(Ljava/lang/reflect/Field;Ljava/lang/Object;)Ljava/lang/Object;";
                                    if (called.equals("set") && descriptor.equals("(Ljava/lang/Object;Ljava/lang/Object;)V"))
                                        replacement = "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V";
                                    if (called.equals("getDeclaringClass") && descriptor.equals("()Ljava/lang/Class;"))
                                        replacement = "(Ljava/lang/reflect/Field;)Ljava/lang/Class;";
                                }
                            }
                            if (opcode == Opcodes.INVOKEINTERFACE && owner.equals("java/lang/reflect/Member")
                                    && called.equals("getDeclaringClass") && descriptor.equals("()Ljava/lang/Class;"))
                                replacement = "(Ljava/lang/reflect/Member;)Ljava/lang/Class;";
                            if (replacement == null) super.visitMethodInsn(opcode, owner, called, descriptor, isInterface);
                            else {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, called, replacement, false);
                            }
                        }
                    };
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : null;
        } catch (Throwable failure) {
            StatusReporter.warn("Gson field hook could not transform " + name + ": " + failure.getClass().getSimpleName());
            return null;
        }
    }
}
