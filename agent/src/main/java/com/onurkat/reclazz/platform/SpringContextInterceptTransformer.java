/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import com.onurkat.reclazz.ui.StatusReporter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.*;
import com.onurkat.reclazz.ui.Failures;

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
        if ("org/springframework/beans/factory/xml/XmlBeanDefinitionReader".equals(className)) {
            return instrumentXmlReader(classfileBuffer);
        }
        if ("org/springframework/beans/factory/parsing/ReaderContext".equals(className)) {
            return instrumentXmlEvents(classfileBuffer);
        }
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
            StatusReporter.warn("Failed to instrument AbstractApplicationContext: " + Failures.describe(e));
            return null;
        }
    }

    private byte[] instrumentXmlReader(byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            // Straight-line stack-neutral insertion preserves the existing frames.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"doLoadBeanDefinitions".equals(name) || !descriptor.equals(
                            "(Lorg/xml/sax/InputSource;Lorg/springframework/core/io/Resource;)I")) return visitor;
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/onurkat/reclazz/platform/SpringXmlAliases", "begin",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/onurkat/reclazz/platform/SpringXmlResources", "record",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        } catch (Exception failure) {
            StatusReporter.warn("Failed to instrument Spring XML resource capture: " + Failures.describe(failure));
            return null;
        }
    }

    private byte[] instrumentXmlEvents(byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean alias = name.equals("fireAliasRegistered") && descriptor.equals(
                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V");
                    boolean component = name.equals("fireComponentRegistered") && descriptor.equals(
                            "(Lorg/springframework/beans/factory/parsing/ComponentDefinition;)V");
                    if (!alias && !component) return visitor;
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                if (alias) super.visitVarInsn(Opcodes.ALOAD, 2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/onurkat/reclazz/platform/SpringXmlAliases", alias ? "alias" : "component",
                                        alias ? "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V"
                                                : "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        } catch (Exception failure) {
            StatusReporter.warn("Failed to instrument Spring XML alias capture: " + Failures.describe(failure));
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
