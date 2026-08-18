/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;

/**
 * Teaches {@code EnumMap} and {@code EnumSet} to notice that their enum grew.
 *
 * <p>Both size their storage from the enum's constant array when they are
 * built, so an instance that existed before a constant was appended is one slot
 * short and throws from inside {@code java.util} the moment the new constant
 * reaches it. There is no way for a Java agent to find those instances on the
 * heap, so each one is repaired where it is used: a call to the healer is put
 * at the head of every instance method, and it returns immediately unless this
 * instance is actually behind.
 *
 * <h2>Why this is narrower than it sounds</h2>
 *
 * <p>Transforming a JDK collection sounds like the kind of thing that breaks an
 * application at three in the morning, so the blast radius is worth being
 * precise about.
 *
 * <ul>
 *   <li>It is installed lazily, the first time a constant is actually appended.
 *       An application that never reloads an enum runs untouched
 *       {@code java.util} code.</li>
 *   <li>It changes no method's logic. One static call is prepended; every
 *       instruction that was there before is still there, in order.</li>
 *   <li>The call is a static field read that returns immediately until an
 *       append has happened, and after that a length comparison.</li>
 *   <li>It only ever grows an array that the JVM was about to index past the
 *       end of. A program that never triggers the old exception cannot observe
 *       any difference.</li>
 *   <li>It adds no fields and changes no signatures, so it is a body-only
 *       retransformation, which is what makes it legal on a class the JVM has
 *       already loaded.</li>
 * </ul>
 */
public final class EnumCollectionTransformer implements ClassFileTransformer {

    private static final String HEALER = "com/onurkat/reclazz/bootstrap/EnumCollectionHealer";

    /** EnumSet is abstract; the two implementations are where the universe is read. */
    private static final Set<String> TARGETS = Set.of(
            "java/util/EnumMap",
            "java/util/RegularEnumSet",
            "java/util/JumboEnumSet");

    private static volatile boolean installed = false;

    /**
     * Install the transform, once, and retransform the classes that are
     * already loaded.
     *
     * @return null when it is in place, or the reason it is not
     */
    public static synchronized String install(Instrumentation instrumentation) {
        if (installed) return null;
        if (instrumentation == null) return "no instrumentation";
        if (!com.onurkat.reclazz.bootstrap.EnumCollectionHealer.isSupported()) {
            return "this JDK does not have the EnumMap/EnumSet shapes this needs";
        }

        java.util.List<Class<?>> loaded = new java.util.ArrayList<>();
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            String internal = candidate.getName().replace('.', '/');
            if (TARGETS.contains(internal) && instrumentation.isModifiableClass(candidate)) {
                loaded.add(candidate);
            }
        }

        EnumCollectionTransformer transformer = new EnumCollectionTransformer();
        instrumentation.addTransformer(transformer, true);
        try {
            if (!loaded.isEmpty()) {
                instrumentation.retransformClasses(loaded.toArray(new Class<?>[0]));
            }
        } catch (Throwable t) {
            instrumentation.removeTransformer(transformer);
            return "the JVM refused to retransform them: " + t;
        }

        installed = true;
        return null;
    }

    /** Test seam: whether the transform has been put in place. */
    public static boolean isInstalled() {
        return installed;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !TARGETS.contains(className)) return null;
        try {
            return inject(classfileBuffer);
        } catch (Throwable t) {
            // Handing back the original leaves the collection exactly as the
            // JDK shipped it, which is the safe half of this trade.
            return null;
        }
    }

    /** Visible for the test that holds the shape of what is injected. */
    public static byte[] inject(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                boolean isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
                boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;

                // A constructor is the one place the arrays are being built, so
                // healing there would race with the code doing the work.
                if (isStatic || isAbstract || isNative
                        || "<init>".equals(name) || "<clinit>".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HEALER, "heal",
                                "(Ljava/lang/Object;)V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }
}
