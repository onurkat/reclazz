/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;

/**
 * R35: registers every instance of a library class that caches a class's members (by constructor), so a reload that
 * changes members can clear those caches. One kind per class; the caches are cleared in {@code reload.LibraryCaches}.
 */
public final class CacheOwnerTransformer implements ClassFileTransformer {
    static final Map<String, String> OWNERS = Map.of(
            "com/google/gson/Gson", "gson",
            "org/springframework/expression/spel/support/ReflectivePropertyAccessor", "spel",
            "jakarta/el/BeanELResolver", "el",
            "javax/el/BeanELResolver", "el",
            "org/apache/velocity/util/introspection/IntrospectorCache", "velocity",
            "org/apache/commons/beanutils2/PropertyUtilsBean", "beanutils",
            "org/apache/commons/beanutils/PropertyUtilsBean", "beanutils",
            "de/hybris/platform/webservicescommons/mapping/impl/DefaultFieldSetLevelHelper", "occ");
    /** SAP Commerce OCC: the levels configured from XML, recorded after the helper builds or receives them. */
    private static final String OCC = "de/hybris/platform/webservicescommons/mapping/impl/DefaultFieldSetLevelHelper";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        String kind = name == null ? null : OWNERS.get(name);
        if (kind == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, method, desc, signature, exceptions);
                    if (name.equals(OCC) && (method.equals("setApplicationContext") || method.equals("setLevelMap"))) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OCC, "getLevelMap", "()Ljava/util/Map;", false);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/onurkat/reclazz/bootstrap/TrackedInstances",
                                            "baseline", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (!method.equals("<init>")) return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitLdcInsn(kind);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/onurkat/reclazz/bootstrap/TrackedInstances",
                                        "track", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        } catch (Throwable failure) {
            StatusReporter.warn("Cache hook could not transform " + name + ": " + failure.getClass().getSimpleName());
            return null;
        }
    }
}
