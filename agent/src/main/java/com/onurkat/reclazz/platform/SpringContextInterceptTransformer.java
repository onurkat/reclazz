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
        if ("org/springframework/context/annotation/ConfigurationClassParser".equals(className))
            return instrumentConfiguration(classfileBuffer, false);
        if ("org/springframework/context/annotation/ConfigurationClassBeanDefinitionReader".equals(className))
            return instrumentConfiguration(classfileBuffer, true);
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

    private static byte[] instrumentConfiguration(byte[] bytes, boolean readerHook) {
        try {
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            var supported = readerHook
                    ? java.util.Set.of("c8c41daa6b847dfe5320ceb76d865178eee2895a4df0cbe7dcce4bde6dabe9c8",
                                      "68035e8d2d66267559421f6323e6bce4f0308dac12f413a6c1a1d5e470fdd3d4")
                    : java.util.Set.of("f8473bcddf6600bce163ad716f59d556068d1a9b411e14082833c67609065435",
                                      "79784d67eb1010714d05c9e6eb0dd3678dcffe435490c677cceefa127bdda2c7");
            if (!supported.contains(sha)) return null;
        } catch (java.security.NoSuchAlgorithmException unavailable) { return null; }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(readerHook ? "loadBeanDefinitions" : "parse") || !descriptor.equals("(Ljava/util/Set;)V")) return target;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            if (readerHook) super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/onurkat/reclazz/spring/SpringConfigurationOwners",
                                    readerHook ? "definitionsLoaded" : "parsed",
                                    readerHook ? "(Ljava/lang/Object;Ljava/util/Set;)V" : "(Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
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
