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
 * Outside Spring, calls into Spring's ReflectionUtils method walks go to
 * SpringReflectionView, so the caller keeps its own view there too.
 *
 * Registered with canRetransform=true so existing classes can be retransformed
 * on first load.
 *
 * Skips: java/lang/invoke/*, com/onurkat/reclazz/*, sun/*, jdk/* to avoid
 * infinite recursion and issues with core JDK classes.
 */
public class ReflectionInterceptTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/ReflectionBridge";
    private static final String SPRING_UTILS = "org/springframework/util/ReflectionUtils";
    private static final String SPRING_VIEW = "com/onurkat/reclazz/bootstrap/SpringReflectionView";

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

            // Most classes call none of these, and this transformer sees
            // every class the JVM loads, so the cost of saying "no" is the
            // cost of the agent at startup.
            boolean spring = className.startsWith("org/springframework/");
            if (!constantPoolMentionsTarget(reader, !spring)) return null;

            // COMPUTE_MAXS only, this transform rewrites invokevirtual→invokestatic with
            // identical stack effects, so frames are unchanged. COMPUTE_FRAMES would require
            // a SafeClassWriter and is too expensive for this broad (all-class) transform.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ReflectionRewriteVisitor(writer, spring);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            return writer.toByteArray();
        } catch (Exception e) {
            // Never fail a transform, return null to use original bytecode
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
     * Whether the class can call one of the intercepted methods at all,
     * answered from the constant pool alone.
     *
     * <p>An {@code invokevirtual} on {@code java/lang/Class} needs a
     * {@code CONSTANT_Methodref} naming that class and that method, so a pool
     * without one is a class without such a call, and no method body needs
     * visiting to know it. This replaced a visitor that walked every
     * instruction of every method of every loaded class to find the same
     * entries; over a 17,700-class Spring and Hibernate corpus the transformer
     * took 518ms cold and about 200ms warm with that walk, and 138ms cold and
     * 43 to 60ms warm with this one, rewriting the same 170 classes either
     * way. A pool entry no instruction uses would make
     * this say yes to a class the rewrite then leaves as it was, which costs
     * one full pass and changes nothing.
     */
    static boolean constantPoolMentionsTarget(ClassReader reader) {
        return constantPoolMentionsTarget(reader, true);
    }

    /** @param springUtilities whether a call into Spring's ReflectionUtils counts too */
    static boolean constantPoolMentionsTarget(ClassReader reader, boolean springUtilities) {
        char[] buffer = new char[reader.getMaxStringLength()];
        int items = reader.getItemCount();
        for (int i = 1; i < items; i++) {
            int offset = reader.getItem(i);
            // Zero is the unused second slot of a long or double constant.
            if (offset == 0) continue;
            if (reader.readByte(offset - 1) != CONSTANT_METHODREF) continue;
            String owner = reader.readClass(offset, buffer);
            boolean jdk = "java/lang/Class".equals(owner);
            if (!jdk && !(springUtilities && SPRING_UTILS.equals(owner))) continue;
            int nameAndType = reader.getItem(reader.readUnsignedShort(offset + 2));
            String name = reader.readUTF8(nameAndType, buffer);
            String descriptor = reader.readUTF8(nameAndType + 2, buffer);
            if (jdk ? isTargetMethod(name, descriptor) : springViewDescriptor(name, descriptor) != null) {
                return true;
            }
        }
        return false;
    }

    /** JVMS 4.4: the tag of a CONSTANT_Methodref_info entry. */
    private static final int CONSTANT_METHODREF = 10;

    /**
     * ClassVisitor that rewrites reflection call sites in method bodies.
     */
    private static class ReflectionRewriteVisitor extends ClassVisitor {
        private final boolean physicalMethods;
        /** An ldc of a class constant needs class file 49 (Java 5) or later. */
        private boolean classConstants;
        ReflectionRewriteVisitor(ClassVisitor cv, boolean physicalMethods) {
            super(Opcodes.ASM9, cv);
            this.physicalMethods=physicalMethods;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            classConstants = (version & 0xFFFF) >= Opcodes.V1_5;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) return null;
            return new ReflectionRewriteMethodVisitor(mv, physicalMethods, classConstants);
        }
    }

    /**
     * MethodVisitor that intercepts invokevirtual on Class reflection methods
     * and rewrites them to invokestatic on ReflectionBridge.
     */
    private static class ReflectionRewriteMethodVisitor extends MethodVisitor {
        private final boolean physicalMethods;
        private final boolean springView;
        ReflectionRewriteMethodVisitor(MethodVisitor mv, boolean physicalMethods, boolean classConstants) {
            super(Opcodes.ASM9, mv);
            this.physicalMethods=physicalMethods;
            this.springView=!physicalMethods && classConstants;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL && "java/lang/Class".equals(owner)) {
                String bridgeDesc = rewriteDescriptor(name, descriptor);
                if (bridgeDesc != null) {
                    // Rewrite: invokevirtual Class.xxx -> invokestatic ReflectionBridge.xxx
                    // The Class instance (receiver) becomes the first argument
                    // Native Spring lifecycle scans must not register companion
                    // callbacks before their dedicated adapters can own them.
                    String bridgeName=physicalMethods && (name.equals("getDeclaredMethods")
                            || name.equals("getMethods") || name.equals("getDeclaredMethod") || name.equals("getMethod"))
                            ? name+"ForFramework" : name;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, bridgeName,
                            bridgeDesc, false);
                    return;
                }
            }

            // Application code calling Spring's ReflectionUtils gets the
            // application's view of the class: see SpringReflectionView.
            if (springView && opcode == Opcodes.INVOKESTATIC && SPRING_UTILS.equals(owner)) {
                String viewDesc = springViewDescriptor(name, descriptor);
                if (viewDesc != null) {
                    // Resolved through the caller's loader, so it is the Spring the original call bound to.
                    super.visitLdcInsn(Type.getObjectType(SPRING_UTILS));
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SPRING_VIEW, name, viewDesc, false);
                    return;
                }
            }

            // Pass through unchanged
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    /**
     * The SpringReflectionView descriptor for a ReflectionUtils call it covers,
     * which is the original with the ReflectionUtils class appended, or null.
     */
    static String springViewDescriptor(String name, String descriptor) {
        String callback = "Lorg/springframework/util/ReflectionUtils$MethodCallback;";
        String filter = "Lorg/springframework/util/ReflectionUtils$MethodFilter;";
        return switch (name) {
            case "doWithMethods" -> {
                if (("(Ljava/lang/Class;" + callback + ")V").equals(descriptor))
                    yield "(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/Class;)V";
                if (("(Ljava/lang/Class;" + callback + filter + ")V").equals(descriptor))
                    yield "(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;)V";
                yield null;
            }
            case "doWithLocalMethods" -> ("(Ljava/lang/Class;" + callback + ")V").equals(descriptor)
                    ? "(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/Class;)V" : null;
            case "findMethod" -> {
                if ("(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Method;".equals(descriptor))
                    yield "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/reflect/Method;";
                if ("(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(descriptor))
                    yield "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/reflect/Method;";
                yield null;
            }
            default -> null;
        };
    }

    /**
     * Check if a method call on java/lang/Class is one we want to intercept.
     */
    static boolean isTargetMethod(String name, String descriptor) {
        return switch (name) {
            case "getDeclaredMethods" -> "()[Ljava/lang/reflect/Method;".equals(descriptor);
            case "getDeclaredFields" -> "()[Ljava/lang/reflect/Field;".equals(descriptor);
            case "getDeclaredMethod" ->
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(descriptor);
            case "getDeclaredField" ->
                    "(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(descriptor);
            case "getMethods" -> "()[Ljava/lang/reflect/Method;".equals(descriptor);
            case "getFields" -> "()[Ljava/lang/reflect/Field;".equals(descriptor);
            case "getMethod" ->
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(descriptor);
            case "getField" ->
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
            case "getMethods" -> {
                if ("()[Ljava/lang/reflect/Method;".equals(descriptor))
                    yield "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;";
                yield null;
            }
            case "getFields" -> {
                if ("()[Ljava/lang/reflect/Field;".equals(descriptor))
                    yield "(Ljava/lang/Class;)[Ljava/lang/reflect/Field;";
                yield null;
            }
            case "getMethod" -> {
                if ("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(descriptor))
                    yield "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;";
                yield null;
            }
            case "getField" -> {
                if ("(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(descriptor))
                    yield "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;";
                yield null;
            }
            default -> null;
        };
    }
}
