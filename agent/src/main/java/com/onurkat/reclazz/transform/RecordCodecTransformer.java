/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Method-body hooks only: safe for already loaded Jackson codecs too. */
public final class RecordCodecTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/RecordCodecs";
    private static String simple(String name) {
        for (String prefix : new String[]{"com/fasterxml/jackson/databind/", "tools/jackson/databind/"})
            if (name.startsWith(prefix)) return name.substring(prefix.length());
        return "";
    }
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (name == null) return null;
        String type = simple(name);
        if (!type.equals("ObjectMapper") && !type.equals("ObjectReader")
                && !type.equals("ObjectWriter") && !type.equals("MappingIterator")) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, descriptor, signature, exceptions)) {
                    @Override public void visitCode() {
                        super.visitCode();
                        // A mapper may predate dynamic attachment. First actual use records that exact instance.
                        if (type.equals("ObjectMapper") && !method.equals("<init>")
                                && (access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) == 0) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/onurkat/reclazz/bootstrap/JacksonMapperRegistry", "track", "(Ljava/lang/Object;)V", false);
                        }
                    }
                    @Override public void visitInsn(int opcode) {
                        if (method.equals("<init>") && opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, type.equals("ObjectMapper")
                                    ? "com/onurkat/reclazz/bootstrap/JacksonMapperRegistry" : BRIDGE,
                                    "track", "(Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        boolean cached = opcode == Opcodes.GETFIELD && owner.equals(name) && !method.equals("<init>")
                                && ((type.equals("ObjectReader") && field.equals("_rootDeserializer"))
                                || (type.equals("ObjectWriter") && field.equals("_prefetch"))
                                || (type.equals("MappingIterator") && field.equals("_deserializer")));
                        if (cached) super.visitInsn(Opcodes.DUP);
                        super.visitFieldInsn(opcode, owner, field, desc);
                        if (cached) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "current",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(desc).getInternalName());
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
