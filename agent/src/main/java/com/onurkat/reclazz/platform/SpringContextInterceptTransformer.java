/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import net.bytebuddy.jar.asm.*;

/**
 * ClassFileTransformer that intercepts AbstractApplicationContext.refresh() to capture
 * Spring ApplicationContext instances via ApplicationContextHolder.register().
 *
 * When AbstractApplicationContext.refresh() completes, this transformer appends a call
 * to ApplicationContextHolder.register(this) so the agent can discover the context
 * without requiring Hybris Registry or application code changes.
 *
 * Must be registered before Spring classes are loaded (early in premain).
 */
public class SpringContextInterceptTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "org/springframework/context/support/AbstractApplicationContext";
    private static final String HOLDER_CLASS = "com/onurkat/reclazz/platform/ApplicationContextHolder";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new RefreshMethodVisitor(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            StatusReporter.info("Instrumented AbstractApplicationContext.refresh() for context capture");
            return cw.toByteArray();
        } catch (Exception e) {
            StatusReporter.warn("Failed to instrument AbstractApplicationContext: " + e.getMessage());
            return null;
        }
    }

    private static class RefreshMethodVisitor extends ClassVisitor {
        RefreshMethodVisitor(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("refresh".equals(name) && "()V".equals(descriptor)) {
                return new RefreshAdviceAdapter(mv, access, name, descriptor);
            }
            return mv;
        }
    }

    /**
     * Appends ApplicationContextHolder.register(this) before every RETURN in refresh().
     */
    private static class RefreshAdviceAdapter extends MethodVisitor {
        RefreshAdviceAdapter(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                // Push 'this' onto stack and call ApplicationContextHolder.register(this)
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOLDER_CLASS,
                        "register", "(Ljava/lang/Object;)V", false);
            }
            super.visitInsn(opcode);
        }
    }

    /**
     * ClassWriter that falls back to "java/lang/Object" when getCommonSuperClass
     * can't load a class via Class.forName(). Prevents ClassNotFoundException
     * during COMPUTE_FRAMES when Spring classes reference types not yet loaded.
     */
    private static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader classReader, int flags) {
            super(classReader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Exception e) {
                return "java/lang/Object";
            }
        }
    }
}
