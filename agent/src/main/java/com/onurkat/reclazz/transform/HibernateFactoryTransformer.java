/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;

/** Installed only during premain. The two-argument constructor delegates to the instrumented one. */
public final class HibernateFactoryTransformer implements ClassFileTransformer {
    public static final String FACTORY = "org.hibernate.internal.SessionFactoryImpl";
    public static final String WITNESS = com.onurkat.reclazz.bootstrap.InjectedNames.PREFIX + "hibernateFactoriesTracked";
    private static final String INTERNAL = "org/hibernate/internal/SessionFactoryImpl";
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/HibernateFactories";
    // Original class bytes from hibernate-core 6.5.3.Final. Unmeasured constructor layouts stay conservative.
    private static final String DIGEST = "9b8e68c95987bb8c83191fe6d15d94769a3f83d252e589599f6bbae6750c3772";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> previous, ProtectionDomain domain, byte[] bytes) {
        if (previous != null || !INTERNAL.equals(name)) return null;
        try {
            if (!DIGEST.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) return null;
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            return null;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, method, desc, sig, exceptions);
                if (!method.equals("<init>") || !desc.endsWith("Lorg/hibernate/boot/spi/BootstrapContext;)V")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private void record(String callback) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, INTERNAL, WITNESS + callback, "(Ljava/lang/Object;)V", false);
                    }
                    @Override public void visitMethodInsn(int op, String owner, String called, String descriptor, boolean itf) {
                        super.visitMethodInsn(op, owner, called, descriptor, itf);
                        if (op == Opcodes.INVOKESPECIAL && owner.equals(reader.getSuperName()) && called.equals("<init>"))
                            record("constructing");
                    }
                    @Override public void visitInsn(int op) {
                        if (op == Opcodes.RETURN) record("initialized");
                        super.visitInsn(op);
                    }
                };
            }
            @Override public void visitEnd() {
                // A loader may see Hibernate without seeing the bootstrap bridge. Keep the application
                // constructor working and mark this definition as untracked for all subsequent decisions.
                super.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                        WITNESS, "Z", null, null).visitEnd();
                for (String callback : new String[]{"constructing", "initialized"}) {
                    MethodVisitor method = super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                            WITNESS + callback, "(Ljava/lang/Object;)V", null, null);
                    Label start = new Label(), end = new Label(), failed = new Label();
                    method.visitCode();
                    method.visitTryCatchBlock(start, end, failed, "java/lang/Throwable");
                    method.visitLabel(start);
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, callback, "(Ljava/lang/Object;)V", false);
                    method.visitLabel(end);
                    method.visitInsn(Opcodes.RETURN);
                    method.visitLabel(failed);
                    method.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
                    method.visitInsn(Opcodes.POP);
                    method.visitInsn(Opcodes.ICONST_1);
                    method.visitFieldInsn(Opcodes.PUTSTATIC, INTERNAL, WITNESS, "Z");
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(1, 1);
                    method.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }
}
