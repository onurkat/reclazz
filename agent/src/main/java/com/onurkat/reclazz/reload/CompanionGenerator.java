/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.CallSiteAdapter;
import com.onurkat.reclazz.transform.TransformContext;
import com.onurkat.reclazz.transform.TransformExclusions;
import org.objectweb.asm.*;

import java.util.*;

/**
 * Generates a hidden companion class containing method implementations from
 * a new version of a watched class. The companion class:
 *
 * - Converts instance methods to static methods (receiver becomes first param)
 * - Keeps static methods as static
 * - Uses NESTMATE access to reach private members of the host class
 * - Rewrites field access for new fields to FieldStore calls
 *
 * Known limitation: intra-class method calls (e.g., methodA calling methodB
 * within the same class) are NOT retargeted to the companion. They dispatch
 * to the original class version. This means if both methodA and methodB change,
 * methodA's call to methodB will invoke the old version of methodB.
 */
public class CompanionGenerator implements Opcodes {

    private static final String FIELD_STORE = "com/onurkat/reclazz/bootstrap/FieldStore";
    private static final String PROTECTED_CALL_RESOLVER =
            "com/onurkat/reclazz/bootstrap/ProtectedCallResolver";
    private static final String PROTECTED_CALL_BSM_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;"
            + "Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;"
            + "Ljava/lang/String;"
            + "Ljava/lang/String;"
            + "I)Ljava/lang/invoke/CallSite;";

    /**
     * Generate companion class bytecode.
     *
     * @param originalClassName internal name of the original class
     * @param newBytecode       bytecode of the new version of the class
     * @param diff              structural diff from analysis
     * @param version           version number for naming
     * @return bytecode for the companion class
     */
    public static CompanionResult generate(String originalClassName, byte[] newBytecode,
                                            StructuralAnalyzer.StructuralDiff diff,
                                            int version) {
        ClassReader reader = new ClassReader(newBytecode);
        String companionName = originalClassName + "$$Reclazz$v" + version;

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // Companion class name and hidden classes may not be resolvable via
                // Class.forName during frame computation. Fall back to Object safely.
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Exception e) {
                    return "java/lang/Object";
                }
            }
        };

        // Companion extends Object, implements nothing
        writer.visit(V17, ACC_PUBLIC | ACC_SYNTHETIC, companionName, null,
                "java/lang/Object", null);

        Map<String, String> methodHandleKeys = new LinkedHashMap<>();

        // Collect new fields that need FieldStore
        Set<String> addedFieldKeys = diff.getAddedFields();

        // Visit original class to extract method bodies
        CompanionMethodExtractor extractor = new CompanionMethodExtractor(
                writer, originalClassName, companionName, addedFieldKeys, methodHandleKeys, diff);
        reader.accept(extractor, ClassReader.EXPAND_FRAMES);

        writer.visitEnd();

        return new CompanionResult(writer.toByteArray(), companionName, methodHandleKeys);
    }

    /**
     * Extracts methods from the new class version and writes them as companion methods.
     */
    private static class CompanionMethodExtractor extends ClassVisitor {
        private final ClassWriter writer;
        private final String originalClass;
        private final String companionName;
        private final Set<String> addedFields;
        private final Map<String, String> methodHandleKeys;
        private final StructuralAnalyzer.StructuralDiff diff;

        CompanionMethodExtractor(ClassWriter writer, String originalClass, String companionName,
                                  Set<String> addedFields, Map<String, String> methodHandleKeys,
                                  StructuralAnalyzer.StructuralDiff diff) {
            super(ASM9);
            this.writer = writer;
            this.originalClass = originalClass;
            this.companionName = companionName;
            this.addedFields = addedFields;
            this.methodHandleKeys = methodHandleKeys;
            this.diff = diff;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            // Skip constructors and class initializers
            if ("<init>".equals(name) || "<clinit>".equals(name)) return null;

            // Skip methods that should not be trampolined
            if (TransformExclusions.shouldSkipMethod(originalClass, name, descriptor, access)) {
                return null;
            }

            boolean isStatic = (access & ACC_STATIC) != 0;
            String descHash = CallSiteAdapter.descHash(descriptor);

            String companionMethodName;
            String companionDescriptor;
            String siteKey;

            if (isStatic) {
                companionMethodName = name;
                companionDescriptor = descriptor;
                siteKey = "static:" + name + ":" + descHash;
            } else {
                // Convert instance method to static: prepend receiver type
                companionMethodName = name;
                companionDescriptor = "(L" + originalClass + ";" + descriptor.substring(1);
                siteKey = name + ":" + descHash;
            }

            // Record the method handle key mapping
            methodHandleKeys.put(siteKey, companionMethodName + companionDescriptor);

            // Create the method in the companion class (always static)
            int companionAccess = ACC_PUBLIC | ACC_STATIC;
            MethodVisitor mv = writer.visitMethod(companionAccess, companionMethodName,
                    companionDescriptor, null, exceptions);

            // Return a method visitor that rewrites the method body
            return new CompanionMethodAdapter(mv, originalClass, companionName,
                    addedFields, isStatic);
        }
    }

    /**
     * Adapts method body bytecode for the companion class:
     * - Rewrites field access for added fields to FieldStore helper calls
     * - Regular field access works via NESTMATE access to original class
     */
    private static class CompanionMethodAdapter extends MethodVisitor {
        private final String originalClass;
        private final String companionName;
        private final Set<String> addedFields;
        private final boolean wasStatic;

        CompanionMethodAdapter(MethodVisitor mv, String originalClass, String companionName,
                                Set<String> addedFields, boolean wasStatic) {
            super(ASM9, mv);
            this.originalClass = originalClass;
            this.companionName = companionName;
            this.addedFields = addedFields;
            this.wasStatic = wasStatic;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // Check if this is access to an added field — use FieldStore helpers
            if (owner.equals(originalClass) && addedFields.contains(name + ":" + descriptor)) {
                switch (opcode) {
                    case GETFIELD -> {
                        // Stack: objectref -> value
                        // Call FieldStore.getExtField(instance, className, fieldName, desc) -> Object
                        mv.visitLdcInsn(originalClass);
                        mv.visitLdcInsn(name);
                        mv.visitLdcInsn(descriptor);
                        mv.visitMethodInsn(INVOKESTATIC, FIELD_STORE, "getExtField",
                                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                                false);
                        // Unbox to expected type
                        Type fieldType = Type.getType(descriptor);
                        unbox(mv, fieldType);
                        return;
                    }
                    case PUTFIELD -> {
                        // Stack: objectref, value -> ()
                        // Box value, then call FieldStore.putExtField(instance, boxedValue, className, fieldName, desc)
                        Type fieldType = Type.getType(descriptor);
                        box(mv, fieldType);
                        // Stack: objectref, boxedValue
                        mv.visitLdcInsn(originalClass);
                        mv.visitLdcInsn(name);
                        mv.visitLdcInsn(descriptor);
                        mv.visitMethodInsn(INVOKESTATIC, FIELD_STORE, "putExtField",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                                false);
                        return;
                    }
                    case GETSTATIC, PUTSTATIC -> {
                        // Static fields on the original class that are "added" is unusual.
                        // Fall through to regular access — companion has NESTMATE access.
                    }
                }
            }

            // Regular field access — companion has NESTMATE access to original class
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        /**
         * Intercept method invocations and rewrite cross-package calls to
         * {@code invokedynamic} pointing at
         * {@link com.onurkat.reclazz.bootstrap.ProtectedCallResolver}.
         *
         * <p>A companion class's static method body contains copies of
         * the target class's original instance-method bytecode. In the
         * original class that bytecode ran in the context of a legitimate
         * subclass of the declaring class and passed Java's protected /
         * package-private access check. In the companion (which extends
         * Object and lives in its own hidden-class runtime package), the
         * same instructions fail with {@code IllegalAccessError}.
         *
         * <p>The fix rewrites every cross-package invocation to an
         * {@code invokedynamic} whose bootstrap resolves the MethodHandle
         * through the TARGET class's own
         * {@code __reclazz$lookup MethodHandles.Lookup}, which has
         * private access including all inherited protected members. All
         * access checks happen at resolve time inside the bsm; after that
         * the call site is a {@link java.lang.invoke.ConstantCallSite}
         * with no per-call access cost. Same-package calls keep their
         * original opcode — same-package access rules always pass from
         * the companion's hidden package because the hidden class is in
         * the same classloader nest and shares the binary-name prefix.
         */
        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (shouldRewriteCall(opcode, owner, name)) {
                int kind = kindOf(opcode);
                String invokeDynamicDesc = invokeDynamicDescriptor(opcode, owner, descriptor);

                Handle bsm = new Handle(H_INVOKESTATIC, PROTECTED_CALL_RESOLVER,
                        "protectedCall", PROTECTED_CALL_BSM_DESC, false);

                mv.visitInvokeDynamicInsn(name, invokeDynamicDesc, bsm,
                        owner,          // declaring class internal name
                        originalClass,  // target class internal name
                        kind);
                return;
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /**
         * True if this call must be rewritten as an invokedynamic to
         * preserve original access rights. We rewrite ALL non-static
         * instance method calls because:
         *
         * <ul>
         *   <li>The bytecode owner that javac records for an instance
         *       call is the static type of the receiver, NOT the actual
         *       declaring class. {@code ChildClass.greet() { return
         *       getSecret(); }} compiles to
         *       {@code INVOKEVIRTUAL ChildClass.getSecret} even though
         *       {@code getSecret} is declared on a superclass in a
         *       different package. The JVM's runtime resolution walks
         *       inheritance to find the actual method, then runs the
         *       access check against the ACTUAL declaring class — which
         *       fails from the companion (a hidden class with no
         *       subclass relationship to the declarer).</li>
         *   <li>Without loading the target's class hierarchy and
         *       walking inherited methods at companion-gen time, we
         *       can't tell which calls are "self-only" (NESTMATE
         *       accessible) from inherited ones. A blanket rewrite is
         *       correct and avoids the classloading complexity.</li>
         *   <li>The bsm uses {@code targetLookup.findVirtual(owner, ...)} —
         *       findVirtual walks inheritance from {@code owner}, finds
         *       the actual method, and the access check against
         *       targetLookup's PRIVATE-mode lookup succeeds because the
         *       target class IS a legitimate subclass of every class in
         *       its inheritance chain.</li>
         * </ul>
         *
         * <p>Performance: post-JIT a {@link java.lang.invoke.ConstantCallSite}
         * is essentially a direct call. The only real cost is one bsm
         * invocation per call site at first use.
         *
         * <p>Static calls are exempt — they don't have receiver-type
         * issues and the access rules collapse to plain class-level
         * checks that hidden classes also pass for public targets.
         * Array {@code clone()} and similar are exempt by virtue of
         * having an array-type owner string starting with {@code [}.
         */
        private boolean shouldRewriteCall(int opcode, String owner, String name) {
            if (opcode != INVOKEVIRTUAL
                    && opcode != INVOKESPECIAL
                    && opcode != INVOKEINTERFACE
                    && opcode != INVOKESTATIC) {
                return false;
            }
            if (owner.startsWith("[")) return false;
            // Never rewrite <init>/<clinit>: the JVMS forbids invokedynamic
            // call sites with internal method names, so `new Watched(...)`
            // inside a companion produced "VerifyError: Illegal call to
            // internal method". Constructors keep their INVOKESPECIAL —
            // access-wise safe because the companion is a NESTMATE of the
            // original class and shares its package.
            if (name.startsWith("<")) return false;
            return true;
        }

        private static int kindOf(int opcode) {
            return switch (opcode) {
                case INVOKEVIRTUAL   -> 0; // KIND_VIRTUAL
                case INVOKESPECIAL   -> 1; // KIND_SPECIAL
                case INVOKESTATIC    -> 2; // KIND_STATIC
                case INVOKEINTERFACE -> 3; // KIND_INTERFACE
                default -> throw new IllegalArgumentException("Unsupported opcode: " + opcode);
            };
        }

        /**
         * Compute the invokedynamic descriptor from the original invocation.
         * For instance calls the receiver becomes the first parameter; for
         * static calls the descriptor is unchanged.
         */
        private static String invokeDynamicDescriptor(int opcode, String owner, String descriptor) {
            if (opcode == INVOKESTATIC) {
                return descriptor;
            }
            return "(L" + owner + ";" + descriptor.substring(1);
        }

        private static void unbox(MethodVisitor mv, Type type) {
            switch (type.getSort()) {
                case Type.INT -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                }
                case Type.LONG -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Long");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
                }
                case Type.FLOAT -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Float");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
                }
                case Type.DOUBLE -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                }
                case Type.BOOLEAN -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                }
                case Type.BYTE -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Byte");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                }
                case Type.SHORT -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Short");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                }
                case Type.CHAR -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                }
                default -> {
                    // Object type — just cast
                    String internalName = type.getInternalName();
                    if (!"java/lang/Object".equals(internalName)) {
                        mv.visitTypeInsn(CHECKCAST, internalName);
                    }
                }
            }
        }

        private static void box(MethodVisitor mv, Type type) {
            switch (type.getSort()) {
                case Type.INT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer",
                        "valueOf", "(I)Ljava/lang/Integer;", false);
                case Type.LONG -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",
                        "valueOf", "(J)Ljava/lang/Long;", false);
                case Type.FLOAT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float",
                        "valueOf", "(F)Ljava/lang/Float;", false);
                case Type.DOUBLE -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double",
                        "valueOf", "(D)Ljava/lang/Double;", false);
                case Type.BOOLEAN -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean",
                        "valueOf", "(Z)Ljava/lang/Boolean;", false);
                case Type.BYTE -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte",
                        "valueOf", "(B)Ljava/lang/Byte;", false);
                case Type.SHORT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short",
                        "valueOf", "(S)Ljava/lang/Short;", false);
                case Type.CHAR -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character",
                        "valueOf", "(C)Ljava/lang/Character;", false);
                // Object types don't need boxing
            }
        }
    }

    /**
     * Result of companion class generation.
     */
    public static class CompanionResult {
        private final byte[] bytecode;
        private final String companionName;
        private final Map<String, String> methodHandleKeys;

        CompanionResult(byte[] bytecode, String companionName, Map<String, String> methodHandleKeys) {
            this.bytecode = bytecode;
            this.companionName = companionName;
            this.methodHandleKeys = methodHandleKeys;
        }

        public byte[] getBytecode() { return bytecode; }
        public String getCompanionName() { return companionName; }
        public Map<String, String> getMethodHandleKeys() { return methodHandleKeys; }
    }
}
