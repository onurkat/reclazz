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
 * A limitation this class used to document turned out to be stale, and it is
 * worth recording why so it does not come back. It said intra-class calls
 * (methodA calling methodB) were not retargeted, so methodA would invoke the
 * old methodB. That was written before the trampoline transform: since every
 * method of the loaded class is now a pure invokedynamic trampoline, a
 * companion body's call to methodB lands on the trampoline and dispatches to
 * the latest target. Measured live across two generations, for a private and
 * a protected callee alike: change A and B together, then change only B, and
 * A observes the second B, not the first. The suspect one-shot binding in the
 * rewritten call sites does not occur, because the bootstrap hands back the
 * same MutableCallSite the dispatch table retargets.
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
        return generate(originalClassName, newBytecode, diff, version, java.util.Set.of());
    }

    /**
     * @param staticsToInitialise "name:desc" of added static fields that still
     *                            need their initial value. The companion gains
     *                            a {@link StaticInitialiserSlicer#INIT_METHOD}
     *                            method for the ones whose initialiser can be
     *                            lifted out of {@code <clinit>} safely; the
     *                            rest come back in the plan as refused, with a
     *                            reason to show the developer.
     */
    public static CompanionResult generate(String originalClassName, byte[] newBytecode,
                                            StructuralAnalyzer.StructuralDiff diff,
                                            int version, Set<String> staticsToInitialise) {
        return generate(originalClassName, newBytecode, diff, version,
                staticsToInitialise, java.util.Set.of());
    }

    /**
     * @param skipMethods {@code name:descriptor} keys of methods to leave out
     *                    of the companion. Used by the per-method superclass
     *                    salvage: a pinned method gets no new companion
     *                    target, so its MutableCallSites are not retargeted
     *                    and keep dispatching to the implementation it had.
     */
    public static CompanionResult generate(String originalClassName, byte[] newBytecode,
                                            StructuralAnalyzer.StructuralDiff diff,
                                            int version, Set<String> staticsToInitialise,
                                            Set<String> skipMethods) {
        ClassReader reader = new ClassReader(newBytecode);
        String companionName = originalClassName + "$$Reclazz$v" + version;

        ClassWriter writer = new com.onurkat.reclazz.transform.SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES);

        // Companion extends Object, implements nothing
        writer.visit(V17, ACC_PUBLIC | ACC_SYNTHETIC, companionName, null,
                "java/lang/Object", null);

        Map<String, String> methodHandleKeys = new LinkedHashMap<>();

        // Collect new fields that need FieldStore
        Set<String> addedFieldKeys = diff.getAddedFields();

        // Visit original class to extract method bodies
        CompanionMethodExtractor extractor = new CompanionMethodExtractor(
                writer, originalClassName, companionName, addedFieldKeys, methodHandleKeys,
                diff, skipMethods);
        reader.accept(extractor, ClassReader.EXPAND_FRAMES);

        // The initial value of an added static field. The slice is written
        // through the same adapter as every other companion method, so a
        // private call or an added field inside an initialiser is rewritten
        // exactly as it would be anywhere else.
        StaticInitialiserSlicer.Plan staticPlan =
                StaticInitialiserSlicer.planFor(newBytecode, staticsToInitialise);
        if (staticPlan.hasCode()) {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    StaticInitialiserSlicer.INIT_METHOD, "()V", null, null);
            MethodVisitor adapter = new CompanionMethodAdapter(mv, originalClassName,
                    companionName, addedFieldKeys, true);
            adapter.visitCode();
            staticPlan.initialiserCode.accept(adapter);
            adapter.visitInsn(RETURN);
            adapter.visitMaxs(0, 0);
            adapter.visitEnd();
        }

        writer.visitEnd();

        return new CompanionResult(writer.toByteArray(), companionName, methodHandleKeys, staticPlan);
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
        private final Set<String> skipMethods;

        CompanionMethodExtractor(ClassWriter writer, String originalClass, String companionName,
                                  Set<String> addedFields, Map<String, String> methodHandleKeys,
                                  StructuralAnalyzer.StructuralDiff diff, Set<String> skipMethods) {
            super(ASM9);
            this.writer = writer;
            this.originalClass = originalClass;
            this.companionName = companionName;
            this.addedFields = addedFields;
            this.methodHandleKeys = methodHandleKeys;
            this.diff = diff;
            this.skipMethods = skipMethods;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            // Skip constructors and class initializers
            if ("<init>".equals(name) || "<clinit>".equals(name)) return null;

            // Skip methods that should not be trampolined. Lambda bodies are
            // the exception: the load-time transform skips them because
            // trampolining a method the LambdaMetafactory resolves by direct
            // MethodHandle broke the link, but the companion does not
            // trampoline anything, it carries plain copies, and the copy is
            // exactly what the re-pointed lambda handle in a reloaded body
            // resolves against. Without it, editing a lambda into a method
            // was NoSuchMethodError at first call (measured; the handle
            // named a companion method that was never emitted).
            boolean lambdaBody = name.startsWith("lambda$");
            if (!lambdaBody
                    && TransformExclusions.shouldSkipMethod(originalClass, name, descriptor, access)) {
                return null;
            }
            if (lambdaBody && (access & (ACC_NATIVE | ACC_ABSTRACT)) != 0) {
                return null;
            }

            // A pinned method keeps the implementation it has: no companion
            // body, no methodHandleKeys entry, so retargetAll never touches
            // its call sites.
            if (skipMethods.contains(name + ":" + descriptor)) {
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

            // Record the method handle key mapping. Not for lambda bodies:
            // they have no trampoline site in the original class to retarget,
            // only the copy here that lambda handles are re-pointed at.
            if (!lambdaBody) {
                methodHandleKeys.put(siteKey, companionMethodName + companionDescriptor);
            }

            // Create the method in the companion class (always static)
            int companionAccess = ACC_PUBLIC | ACC_STATIC;
            MethodVisitor mv = writer.visitMethod(companionAccess, companionMethodName,
                    companionDescriptor, null, exceptions);

            // Return a method visitor that rewrites the method body
            return new CompanionMethodAdapter(mv, originalClass, companionName,
                    addedFields, diff.getAddedMethods(), isStatic);
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
        private final Set<String> addedMethods;
        private final boolean wasStatic;

        CompanionMethodAdapter(MethodVisitor mv, String originalClass, String companionName,
                                Set<String> addedFields, boolean wasStatic) {
            this(mv, originalClass, companionName, addedFields, Set.of(), wasStatic);
        }

        CompanionMethodAdapter(MethodVisitor mv, String originalClass, String companionName,
                                Set<String> addedFields, Set<String> addedMethods,
                                boolean wasStatic) {
            super(ASM9, mv);
            this.originalClass = originalClass;
            this.companionName = companionName;
            this.addedFields = addedFields;
            this.addedMethods = addedMethods;
            this.wasStatic = wasStatic;
        }

        /**
         * Re-point method-handle constants that name this class's own methods
         * at the companion, so a lambda in a reloaded body links.
         *
         * <p>javac compiles a lambda to a synthetic {@code lambda$...} method
         * plus an {@code invokedynamic} whose bootstrap arguments carry a
         * MethodHandle to it. The instruction copies into the companion
         * verbatim, so the handle kept naming the original class; for a lambda
         * added by a reload the method is not there (nothing can add one), and
         * LambdaMetafactory answered
         * {@code NoSuchMethodError: demo.Api.lambda$cacheops$0} out of code
         * whose source looked perfectly ordinary. Every {@code lambda$} handle
         * is re-pointed, not only the added ones: the synthetic is private and
         * never overridden, so dispatch cannot be wrong, and the companion's
         * copy is the newest body where the original still has the old one. A
         * method reference to an added method ({@code this::newHelper}) has
         * the same missing-method shape and is re-pointed by the added-methods
         * set. Handles to methods that exist on the loaded class keep their
         * owner: a virtual method reference must keep virtual dispatch.
         *
         * <p>A hidden class may reference its own name in its constant pool
         * ({@code defineHiddenClass} resolves the self-reference), which is
         * what makes the companion a valid owner here. Instance methods live
         * in the companion as statics with the receiver prepended, so the
         * handle's kind and descriptor are lifted the same way.
         */
        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm,
                                           Object... bootstrapMethodArguments) {
            Object[] args = bootstrapMethodArguments;
            Object[] rewritten = null;
            for (int i = 0; i < args.length; i++) {
                if (!(args[i] instanceof Handle handle)) continue;
                if (!handle.getOwner().equals(originalClass)) continue;
                boolean isLambdaBody = handle.getName().startsWith("lambda$");
                boolean isAddedMethod = addedMethods.contains(
                        handle.getName() + ":" + handle.getDesc());
                if (!isLambdaBody && !isAddedMethod) continue;

                Handle lifted;
                if (handle.getTag() == H_INVOKESTATIC) {
                    lifted = new Handle(H_INVOKESTATIC, companionName,
                            handle.getName(), handle.getDesc(), false);
                } else {
                    // Instance impl (a lambda capturing this, or an instance
                    // method reference): the companion's copy is static with
                    // the receiver as its first parameter, which is the other
                    // implMethod shape a lambda linker accepts.
                    lifted = new Handle(H_INVOKESTATIC, companionName, handle.getName(),
                            "(L" + originalClass + ";" + handle.getDesc().substring(1), false);
                }
                if (rewritten == null) rewritten = args.clone();
                rewritten[i] = lifted;
            }

            // A handle re-pointed at the companion resolves (a hidden class
            // may reference itself), but LambdaMetafactory then spins a proxy
            // that calls the implementation BY NAME, and a hidden class has no
            // resolvable name: ClassNotFoundException at the lambda's first
            // call (measured on JDK 21). Those sites link through Reclazz's
            // own factory instead, which uses the already-resolved handle and
            // never a name.
            Handle effectiveBsm = bsm;
            if (rewritten != null
                    && bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                effectiveBsm = new Handle(H_INVOKESTATIC,
                        "com/onurkat/reclazz/bootstrap/LambdaFactory",
                        bsm.getName(), bsm.getDesc(), false);
            }
            super.visitInvokeDynamicInsn(name, descriptor, effectiveBsm,
                    rewritten != null ? rewritten : args);
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
                    case GETSTATIC -> {
                        // Stack: -> value
                        // A static field added after startup is not in the loaded
                        // class's schema, so a plain GETSTATIC here throws
                        // NoSuchFieldError. It used to fall through on the
                        // assumption that adding a static field was unusual; it
                        // is not, and the throw killed the thread after the
                        // reload had already reported success.
                        // The owner Class as a constant, not its name: the
                        // storage is keyed by Class so it collects with the
                        // class instead of pinning its loader. LDC of a class
                        // constant resolves through the companion's own loader,
                        // which is the reloaded class's loader, to that exact
                        // Class. The rewrite only fires when owner equals the
                        // reloaded class (see the guard above), so this is
                        // always a class the companion can resolve.
                        mv.visitLdcInsn(Type.getObjectType(originalClass));
                        mv.visitLdcInsn(name);
                        mv.visitLdcInsn(descriptor);
                        mv.visitMethodInsn(INVOKESTATIC, FIELD_STORE, "getStaticExtField",
                                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                                false);
                        unbox(mv, Type.getType(descriptor));
                        return;
                    }
                    case PUTSTATIC -> {
                        // Stack: value -> ()
                        Type fieldType = Type.getType(descriptor);
                        box(mv, fieldType);
                        // Owner Class constant, see the GETSTATIC note above.
                        mv.visitLdcInsn(Type.getObjectType(originalClass));
                        mv.visitLdcInsn(name);
                        mv.visitLdcInsn(descriptor);
                        // Stack now (boxedValue, ownerClass, name, desc)
                        mv.visitMethodInsn(INVOKESTATIC, FIELD_STORE, "putStaticExtFieldSwapped",
                                "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)V",
                                false);
                        return;
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
        private final StaticInitialiserSlicer.Plan staticPlan;

        CompanionResult(byte[] bytecode, String companionName, Map<String, String> methodHandleKeys) {
            this(bytecode, companionName, methodHandleKeys,
                    StaticInitialiserSlicer.planFor(new byte[0], java.util.Set.of()));
        }

        CompanionResult(byte[] bytecode, String companionName, Map<String, String> methodHandleKeys,
                        StaticInitialiserSlicer.Plan staticPlan) {
            this.bytecode = bytecode;
            this.companionName = companionName;
            this.methodHandleKeys = methodHandleKeys;
            this.staticPlan = staticPlan;
        }

        public byte[] getBytecode() { return bytecode; }
        public String getCompanionName() { return companionName; }
        public Map<String, String> getMethodHandleKeys() { return methodHandleKeys; }
        public StaticInitialiserSlicer.Plan getStaticPlan() { return staticPlan; }
    }
}
