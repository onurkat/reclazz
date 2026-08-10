/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * Rewrites Class.getDeclaredMethods/Fields call sites across ALL loaded classes
 * to dispatch through ReflectionBridge instead. This ensures that frameworks
 * (Spring MVC, Hibernate, etc.) see structurally-added methods/fields.
 *
 * Registered with canRetransform=true so existing classes can be retransformed
 * on first load.
 *
 * Skips: java/lang/invoke/*, com/onurkat/reclazz/*, sun/*, jdk/* to avoid
 * infinite recursion and issues with core JDK classes.
 */
public class ReflectionInterceptTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/ReflectionBridge";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className == null) return null;

        // Skip classes that must not be intercepted
        if (shouldSkip(className)) return null;

        try {
            ClassReader reader = new ClassReader(classfileBuffer);

            // Quick scan: does this class contain any reflection call sites?
            if (!containsReflectionCalls(reader)) return null;

            // COMPUTE_MAXS only — this transform rewrites invokevirtual→invokestatic with
            // identical stack effects, so frames are unchanged. COMPUTE_FRAMES would require
            // a SafeClassWriter and is too expensive for this broad (all-class) transform.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ReflectionRewriteVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            return writer.toByteArray();
        } catch (Exception e) {
            // Never fail a transform — return null to use original bytecode
            return null;
        }
    }

    private boolean shouldSkip(String className) {
        return className.startsWith("java/lang/invoke/")
                || className.startsWith("com/onurkat/reclazz/")
                || className.startsWith("sun/")
                || className.startsWith("jdk/")
                || className.startsWith("java/lang/Class")
                || className.startsWith("java/lang/reflect/")
                || className.startsWith("java/lang/System")
                // ASM itself (shaded or not)
                || className.startsWith("org/objectweb/asm/");
    }

    /**
     * Quick scan to check if the class bytecode contains any invokevirtual
     * calls to Class.getDeclaredMethods/Fields/Method/Field.
     * Avoids the cost of full ClassVisitor transform for classes that don't
     * use reflection at all.
     */
    private boolean containsReflectionCalls(ClassReader reader) {
        ReflectionCallDetector detector = new ReflectionCallDetector();
        try {
            reader.accept(detector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (ReflectionCallDetector.Found ignored) {
            return true;
        }
        return false;
    }

    /**
     * Fast visitor that throws when it finds a reflection call site.
     */
    private static class ReflectionCallDetector extends ClassVisitor {
        ReflectionCallDetector() {
            super(Opcodes.ASM9);
        }

        static class Found extends RuntimeException {
            static final Found INSTANCE = new Found();
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String mName,
                                            String mDescriptor, boolean isInterface) {
                    if (opcode == Opcodes.INVOKEVIRTUAL && "java/lang/Class".equals(owner)) {
                        if (isTargetMethod(mName, mDescriptor)) {
                            throw Found.INSTANCE;
                        }
                    }
                }
            };
        }
    }

    /**
     * ClassVisitor that rewrites reflection call sites in method bodies.
     */
    private static class ReflectionRewriteVisitor extends ClassVisitor {
        ReflectionRewriteVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) return null;
            return new ReflectionRewriteMethodVisitor(mv);
        }
    }

    /**
     * MethodVisitor that intercepts invokevirtual on Class reflection methods
     * and rewrites them to invokestatic on ReflectionBridge.
     */
    private static class ReflectionRewriteMethodVisitor extends MethodVisitor {
        ReflectionRewriteMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL && "java/lang/Class".equals(owner)) {
                String bridgeDesc = rewriteDescriptor(name, descriptor);
                if (bridgeDesc != null) {
                    // Rewrite: invokevirtual Class.xxx -> invokestatic ReflectionBridge.xxx
                    // The Class instance (receiver) becomes the first argument
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, name,
                            bridgeDesc, false);
                    return;
                }
            }

            // Pass through unchanged
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    /**
     * Check if a method call on java/lang/Class is one we want to intercept.
     */
    private static boolean isTargetMethod(String name, String descriptor) {
        return switch (name) {
            case "getDeclaredMethods" -> "()[Ljava/lang/reflect/Method;".equals(descriptor);
            case "getDeclaredFields" -> "()[Ljava/lang/reflect/Field;".equals(descriptor);
            case "getDeclaredMethod" ->
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(descriptor);
            case "getDeclaredField" ->
                    "(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(descriptor);
            default -> false;
        };
    }

    /**
     * Returns the static bridge descriptor (with Class<?> prepended) for a given
     * reflection method, or null if the method/descriptor isn't intercepted.
     */
    private static String rewriteDescriptor(String name, String descriptor) {
        return switch (name) {
            case "getDeclaredMethods" -> {
                if ("()[Ljava/lang/reflect/Method;".equals(descriptor))
                    yield "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;";
                yield null;
            }
            case "getDeclaredFields" -> {
                if ("()[Ljava/lang/reflect/Field;".equals(descriptor))
                    yield "(Ljava/lang/Class;)[Ljava/lang/reflect/Field;";
                yield null;
            }
            case "getDeclaredMethod" -> {
                if ("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(descriptor))
                    yield "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;";
                yield null;
            }
            case "getDeclaredField" -> {
                if ("(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(descriptor))
                    yield "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;";
                yield null;
            }
            default -> null;
        };
    }
}
