/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

/**
 * Makes template engines announce themselves as they are built.
 *
 * Editing a template does nothing to a running application, because the engine
 * parsed it once and cached the result. Clearing that cache is a single call
 * on the engine, and the only hard part is reaching the engine: application
 * code or Spring builds it and keeps it private, and there is no registry to
 * ask.
 *
 * So the constructor is rewritten to register the instance on the way out.
 * This is the same shape as the Spring context intercept, for the same reason:
 * the alternative is guessing where the object is kept.
 *
 * Only the two engines that matter in this ecosystem are covered. Adding
 * another is one line here plus a clear-method name in TemplateReloader; the
 * cost of covering an engine nobody in the project uses is a constructor
 * rewrite for everyone who does not.
 */
public class TemplateInterceptTransformer implements ClassFileTransformer {

    private static final String REGISTRY =
            "com/onurkat/reclazz/bootstrap/TemplateEngineRegistry";

    /**
     * Thymeleaf's engine and Freemarker's Configuration. Both hold the cache
     * and both expose a way to clear it; see TemplateReloader.
     */
    private static final Set<String> TARGETS = Set.of(
            "org/thymeleaf/TemplateEngine",
            "freemarker/template/Configuration");

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null || !TARGETS.contains(className)) return null;

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // COMPUTE_MAXS, never COMPUTE_FRAMES: frame computation resolves
            // types, and resolving types loads classes from inside a transform,
            // which is how a class ends up permanently uninstrumented. Adding
            // two instructions to a constructor does not need frames recomputed.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new RegisterOnConstruction(writer), 0);
            return writer.toByteArray();
        } catch (Throwable t) {
            // A template cache that has to be restarted is a nuisance. An agent
            // that stops an engine from loading is an outage.
            return null;
        }
    }

    private static final class RegisterOnConstruction extends ClassVisitor {
        RegisterOnConstruction(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!"<init>".equals(name)) return mv;

            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitInsn(int opcode) {
                    // On the way out, not on the way in: a constructor that
                    // throws has not produced an engine worth registering, and
                    // registering before super() would not verify anyway.
                    if (opcode == Opcodes.RETURN) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register",
                                "(Ljava/lang/Object;)V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }
    }
}
