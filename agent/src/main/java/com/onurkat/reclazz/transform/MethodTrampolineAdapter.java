/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.AnnotationNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ASM ClassVisitor that rewrites watched classes for invokedynamic dispatch:
 *
 * 1. For each non-excluded method:
 *    - Rename original to __reclazz$v0$<name>$<descHash> (private synthetic)
 *    - Generate replacement with original name/desc: single invokedynamic trampoline
 *
 * 2. Add instance field: Object[] __reclazz$ext (for dynamic field storage)
 * 3. Add static field: MethodHandles.Lookup __reclazz$lookup
 * 4. Inject __reclazz$ext initialization in every <init>
 * 5. Inject __reclazz$lookup initialization in <clinit>
 */
public class MethodTrampolineAdapter extends ClassVisitor implements Opcodes {

    private static final String BOOTSTRAP_CLASS = "com/onurkat/reclazz/bootstrap/ReclazzBootstrap";
    private static final String EXT_FIELD = "__reclazz$ext";
    private static final String LOOKUP_FIELD = "__reclazz$lookup";
    private static final int INITIAL_EXT_SIZE = 8;

    private final TransformContext context;
    private String className;
    private String superName;
    private java.util.Set<String> declaredInterfaces = java.util.Set.of();
    private boolean isInterface;
    private boolean isEnum;
    private boolean hasClinitMethod = false;

    // Collected method info for generating trampolines after visiting all methods
    private final List<MethodInfo> trampolineMethods = new ArrayList<>();

    // Metadata collection
    private final List<TransformContext.MethodSig> methodSigs = new ArrayList<>();
    private final List<TransformContext.FieldSig> fieldSigs = new ArrayList<>();

    // Field declarations collected during visitField, used by FieldAccessAdapter
    // to decide whether to rewrite PUTFIELD/PUTSTATIC. Final fields cannot be
    // safely re-pointed via MethodHandles.findSetter, so we leave their writes
    // as direct PUTFIELD/PUTSTATIC instructions.
    private final java.util.Set<String> declaredFinalFieldKeys = new java.util.HashSet<>();

    /** Annotation signatures of the class as it arrived, before transforming. */
    private final java.util.Set<String> originalAnnotations;

    public MethodTrampolineAdapter(ClassVisitor cv, TransformContext context) {
        this(cv, context, java.util.Set.of());
    }

    public MethodTrampolineAdapter(ClassVisitor cv, TransformContext context,
                                   java.util.Set<String> originalAnnotations) {
        super(ASM9, cv);
        this.context = context;
        this.originalAnnotations = originalAnnotations;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.className = name;
        this.superName = superName;
        this.declaredInterfaces = interfaces == null
                ? java.util.Set.<String>of()
                : java.util.Set.of(interfaces);
        this.isInterface = (access & ACC_INTERFACE) != 0;
        this.isEnum = (access & ACC_ENUM) != 0;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor,
                                    String signature, Object value) {
        fieldSigs.add(new TransformContext.FieldSig(name, descriptor, access));
        if ((access & ACC_FINAL) != 0) {
            declaredFinalFieldKeys.add(name + ":" + descriptor);
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
        methodSigs.add(new TransformContext.MethodSig(name, descriptor, access));

        // Track if <clinit> exists
        if ("<clinit>".equals(name)) {
            hasClinitMethod = true;
            // Return a visitor that injects __reclazz$lookup initialization at the start
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new ClinitInjector(mv);
        }

        // Constructor handling
        if ("<init>".equals(name)) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // Wrap with adapters for field access + call site rewriting within constructor body
            MethodVisitor fieldAdapter = new FieldAccessAdapter(mv, context, className, declaredFinalFieldKeys);
            MethodVisitor callAdapter = new CallSiteAdapter(fieldAdapter, context, className);
            return new InitInjector(callAdapter, descriptor);
        }

        // Check if this method should be skipped
        if (TransformExclusions.shouldSkipMethod(className, name, descriptor, access)) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // Still apply call site and field access adapters within the body
            MethodVisitor fieldAdapter = new FieldAccessAdapter(mv, context, className, declaredFinalFieldKeys);
            return new CallSiteAdapter(fieldAdapter, context, className);
        }

        // This method should be trampolined:
        // 1. Rename the original method
        // 2. Record info for generating the trampoline replacement

        String descHash = CallSiteAdapter.descHash(descriptor);
        String renamedName = "__reclazz$v0$" + name + "$" + descHash;

        // Store info for trampoline generation
        boolean isStatic = (access & ACC_STATIC) != 0;
        boolean isSynchronized = (access & ACC_SYNCHRONIZED) != 0;
        MethodInfo info = new MethodInfo(name, descriptor, access, signature,
                exceptions, renamedName, descHash, isStatic, isSynchronized);
        trampolineMethods.add(info);

        // Write the renamed original method. Keep the original access flags
        // and add ACC_SYNTHETIC so frameworks filter the renamed copy out. We
        // must NOT downgrade to ACC_PRIVATE: private methods are statically
        // bound by the JVM verifier, so findVirtual on a private renamed
        // method skips virtual dispatch and always calls the declaring-class
        // copy. That breaks inheritance: e.g., Container.identity called on an
        // IntContainer instance would invoke Container's renamed method
        // instead of IntContainer's override. Keeping the original
        // visibility lets the JVM resolve the most-derived renamed copy.
        //
        // SYNCHRONIZED is kept here as well, not moved to the trampoline. It
        // was moved, on the reading that every call goes through the
        // trampoline, and that is exactly what this engine stops being true:
        // a call site inside a watched class is rewritten to dispatch to the
        // renamed body, which is the point of it, and a body without the flag
        // takes no monitor. Measured on Spring Boot 3.3.4, stock JDK 21, with
        // no reload at all, two concurrent calls to a synchronized method of a
        // watched class: 6.3 seconds without the agent, 3.3 with it. Attaching
        // Reclazz was quietly removing mutual exclusion from the application's
        // own code, from startup.
        //
        // Both copies carry it now, which is safe because a monitor is
        // reentrant: a call through the trampoline locks the receiver and
        // re-enters on the body, and a rewritten call site locks it once. For
        // a static method the flag locks the declaring class, the same monitor
        // the original had.
        int renamedAccess = access | ACC_SYNTHETIC;
        MethodVisitor mv = super.visitMethod(renamedAccess, renamedName, descriptor, signature, exceptions);
        // Apply call site and field access adapters within the renamed method body
        MethodVisitor fieldAdapter = new FieldAccessAdapter(mv, context, className, declaredFinalFieldKeys);
        MethodVisitor callAdapter = new CallSiteAdapter(fieldAdapter, context, className);
        // Capture parameter names + annotations for trampoline replay so frameworks
        // like Spring (@RequestParam, @PathVariable) can find them via reflection.
        return new MetadataRecordingMethodVisitor(callAdapter, info);
    }

    @Override
    public void visitEnd() {
        // Add __reclazz$ext field (only for non-interface classes)
        if (!isInterface) {
            super.visitField(ACC_PRIVATE | ACC_SYNTHETIC, EXT_FIELD,
                    "[Ljava/lang/Object;", null, null);
        }

        // Add __reclazz$lookup static field
        super.visitField(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, LOOKUP_FIELD,
                "Ljava/lang/invoke/MethodHandles$Lookup;", null, null);

        // Generate trampoline methods
        for (MethodInfo info : trampolineMethods) {
            generateTrampoline(info);
        }

        // If no <clinit> existed, generate one for __reclazz$lookup init
        if (!hasClinitMethod) {
            MethodVisitor mv = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            // __reclazz$lookup = MethodHandles.lookup();
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles",
                    "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
            mv.visitFieldInsn(PUTSTATIC, className, LOOKUP_FIELD,
                    "Ljava/lang/invoke/MethodHandles$Lookup;");
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }

        // Store metadata
        context.putMetadata(className, new TransformContext.ClassMetadata(
                methodSigs, fieldSigs, 0, superName, originalAnnotations, declaredInterfaces));

        super.visitEnd();
    }

    /**
     * Generate a trampoline method that delegates via invokedynamic.
     */
    private void generateTrampoline(MethodInfo info) {
        // Trampoline gets original access + synchronized if applicable
        int trampolineAccess = info.originalAccess;
        MethodVisitor mv = super.visitMethod(trampolineAccess, info.name, info.descriptor,
                info.signature, info.exceptions);

        // Replay captured parameter names so reflection (Spring @RequestParam,
        // @PathVariable, etc.) can find them on the trampoline.
        for (ParamRecord p : info.parameters) {
            mv.visitParameter(p.name, p.access);
        }

        // Replay captured method annotations (e.g. @RequestMapping, @Transactional)
        for (AnnotationRecord ar : info.visibleAnnotations) {
            ar.node.accept(mv.visitAnnotation(ar.node.desc, true));
        }
        for (AnnotationRecord ar : info.invisibleAnnotations) {
            ar.node.accept(mv.visitAnnotation(ar.node.desc, false));
        }

        // Replay captured parameter annotations (e.g. @RequestParam("x"))
        for (Map.Entry<Integer, List<AnnotationRecord>> entry : info.visibleParamAnnotations.entrySet()) {
            int paramIdx = entry.getKey();
            for (AnnotationRecord ar : entry.getValue()) {
                ar.node.accept(mv.visitParameterAnnotation(paramIdx, ar.node.desc, true));
            }
        }
        for (Map.Entry<Integer, List<AnnotationRecord>> entry : info.invisibleParamAnnotations.entrySet()) {
            int paramIdx = entry.getKey();
            for (AnnotationRecord ar : entry.getValue()) {
                ar.node.accept(mv.visitParameterAnnotation(paramIdx, ar.node.desc, false));
            }
        }

        // Replay captured TYPE_USE annotations (JSR 308: @NonNull String x, etc.)
        for (TypeAnnotationRecord tar : info.typeAnnotations) {
            tar.node.accept(mv.visitTypeAnnotation(tar.typeRef, tar.typePath, tar.node.desc, tar.visible));
        }

        mv.visitCode();

        Type methodType = Type.getMethodType(info.descriptor);
        Type[] argTypes = methodType.getArgumentTypes();
        Type returnType = methodType.getReturnType();

        if (info.isStatic) {
            // Static: load all args, invokedynamic with same descriptor
            int slot = 0;
            for (Type argType : argTypes) {
                mv.visitVarInsn(argType.getOpcode(ILOAD), slot);
                slot += argType.getSize();
            }

            Handle bsm = new Handle(H_INVOKESTATIC, BOOTSTRAP_CLASS,
                    "bootstrapStaticMethod",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                            "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)" +
                            "Ljava/lang/invoke/CallSite;",
                    false);
            mv.visitInvokeDynamicInsn(info.name, info.descriptor, bsm, className, info.descHash);
        } else {
            // Instance: load this + all args, invokedynamic with prepended receiver
            mv.visitVarInsn(ALOAD, 0); // this
            int slot = 1;
            for (Type argType : argTypes) {
                mv.visitVarInsn(argType.getOpcode(ILOAD), slot);
                slot += argType.getSize();
            }

            String indyDesc = "(L" + className + ";" + info.descriptor.substring(1);
            Handle bsm = new Handle(H_INVOKESTATIC, BOOTSTRAP_CLASS,
                    "bootstrapMethod",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                            "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)" +
                            "Ljava/lang/invoke/CallSite;",
                    false);
            mv.visitInvokeDynamicInsn(info.name, indyDesc, bsm, className, info.descHash);
        }

        // Return
        mv.visitInsn(returnType.getOpcode(IRETURN));

        // COMPUTE_FRAMES recalculates max stack/locals, so these values are ignored
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Injects __reclazz$ext = new Object[INITIAL_EXT_SIZE] after super/this constructor call.
     *
     * Tracks NEW instructions to distinguish between:
     * - super()/this() constructor calls (where we inject __reclazz$ext init)
     * - new MyClass() / new SuperClass() object creation within constructor args
     */
    private class InitInjector extends MethodVisitor {
        private boolean superInitCalled = false;
        private final String initDescriptor;
        // Track NEW instructions for className/superName to distinguish
        // object creation from super/this constructor calls
        private int newClassCount = 0;
        private int newSuperCount = 0;

        InitInjector(MethodVisitor mv, String descriptor) {
            super(ASM9, mv);
            this.initDescriptor = descriptor;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            super.visitTypeInsn(opcode, type);
            if (!superInitCalled && opcode == NEW) {
                if (type.equals(className)) newClassCount++;
                else if (type.equals(superName)) newSuperCount++;
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

            // After super/this <init> call, inject __reclazz$ext initialization
            if (!superInitCalled && opcode == INVOKESPECIAL && "<init>".equals(name)) {
                if (owner.equals(className) && newClassCount > 0) {
                    // This is for a `new MyClass()` expression, not a this() call
                    newClassCount--;
                } else if (owner.equals(superName) && newSuperCount > 0) {
                    // This is for a `new SuperClass()` expression, not a super() call
                    newSuperCount--;
                } else if (owner.equals(superName) || owner.equals(className)) {
                    // This is the actual super() or this() constructor call
                    superInitCalled = true;

                    if (!MethodTrampolineAdapter.this.isInterface) {
                        // this.__reclazz$ext = new Object[INITIAL_EXT_SIZE];
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitIntInsn(BIPUSH, INITIAL_EXT_SIZE);
                        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                        mv.visitFieldInsn(PUTFIELD, className, EXT_FIELD, "[Ljava/lang/Object;");
                    }
                }
            }
        }
    }

    /**
     * Injects __reclazz$lookup = MethodHandles.lookup() at the beginning of <clinit>.
     */
    private class ClinitInjector extends MethodVisitor {
        ClinitInjector(MethodVisitor mv) {
            super(ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            // __reclazz$lookup = MethodHandles.lookup();
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles",
                    "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
            mv.visitFieldInsn(PUTSTATIC, className, LOOKUP_FIELD,
                    "Ljava/lang/invoke/MethodHandles$Lookup;");
        }
    }

    /**
     * Info collected per trampolined method. Mutable list/map fields are populated
     * by {@link MetadataRecordingMethodVisitor} during the visit pass and replayed
     * onto the trampoline in {@link #generateTrampoline}.
     */
    private static final class MethodInfo {
        final String name;
        final String descriptor;
        final int originalAccess;
        final String signature;
        final String[] exceptions;
        final String renamedName;
        final String descHash;
        final boolean isStatic;
        final boolean isSynchronized;
        final List<ParamRecord> parameters = new ArrayList<>();
        final List<AnnotationRecord> visibleAnnotations = new ArrayList<>();
        final List<AnnotationRecord> invisibleAnnotations = new ArrayList<>();
        final Map<Integer, List<AnnotationRecord>> visibleParamAnnotations = new LinkedHashMap<>();
        final Map<Integer, List<AnnotationRecord>> invisibleParamAnnotations = new LinkedHashMap<>();
        final List<TypeAnnotationRecord> typeAnnotations = new ArrayList<>();

        MethodInfo(String name, String descriptor, int originalAccess, String signature,
                    String[] exceptions, String renamedName, String descHash,
                    boolean isStatic, boolean isSynchronized) {
            this.name = name;
            this.descriptor = descriptor;
            this.originalAccess = originalAccess;
            this.signature = signature;
            this.exceptions = exceptions;
            this.renamedName = renamedName;
            this.descHash = descHash;
            this.isStatic = isStatic;
            this.isSynchronized = isSynchronized;
        }
    }

    private record ParamRecord(String name, int access) {}
    private record AnnotationRecord(AnnotationNode node) {}
    private record TypeAnnotationRecord(int typeRef, TypePath typePath, AnnotationNode node, boolean visible) {}

    /**
     * Captures parameter names + method/parameter annotations while forwarding
     * everything to the underlying delegate (which writes the renamed original
     * method body). The captured data is later replayed onto the trampoline by
     * {@link #generateTrampoline} so reflection on the trampoline returns the
     * same metadata as the original method.
     */
    private static class MetadataRecordingMethodVisitor extends MethodVisitor {
        private final MethodInfo info;

        MetadataRecordingMethodVisitor(MethodVisitor delegate, MethodInfo info) {
            super(ASM9, delegate);
            this.info = info;
        }

        @Override
        public void visitParameter(String name, int access) {
            info.parameters.add(new ParamRecord(name, access));
            super.visitParameter(name, access);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            AnnotationNode node = new AnnotationNode(ASM9, descriptor);
            if (visible) info.visibleAnnotations.add(new AnnotationRecord(node));
            else info.invisibleAnnotations.add(new AnnotationRecord(node));
            AnnotationVisitor delegateAv = super.visitAnnotation(descriptor, visible);
            return new MultiplexAnnotationVisitor(delegateAv, node);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            AnnotationNode node = new AnnotationNode(ASM9, descriptor);
            Map<Integer, List<AnnotationRecord>> target = visible
                    ? info.visibleParamAnnotations
                    : info.invisibleParamAnnotations;
            target.computeIfAbsent(parameter, k -> new ArrayList<>()).add(new AnnotationRecord(node));
            AnnotationVisitor delegateAv = super.visitParameterAnnotation(parameter, descriptor, visible);
            return new MultiplexAnnotationVisitor(delegateAv, node);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                       String descriptor, boolean visible) {
            AnnotationNode node = new AnnotationNode(ASM9, descriptor);
            info.typeAnnotations.add(new TypeAnnotationRecord(typeRef, typePath, node, visible));
            AnnotationVisitor delegateAv = super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
            return new MultiplexAnnotationVisitor(delegateAv, node);
        }
    }

    /**
     * Forwards annotation visit calls to two visitors at once: the first writes
     * to the renamed-original method, the second captures into an AnnotationNode
     * for later trampoline replay. Recurses through nested annotations and arrays.
     */
    private static class MultiplexAnnotationVisitor extends AnnotationVisitor {
        private final AnnotationVisitor delegate;
        private final AnnotationVisitor recorder;

        MultiplexAnnotationVisitor(AnnotationVisitor delegate, AnnotationVisitor recorder) {
            super(ASM9);
            this.delegate = delegate;
            this.recorder = recorder;
        }

        @Override
        public void visit(String name, Object value) {
            if (delegate != null) delegate.visit(name, value);
            if (recorder != null) recorder.visit(name, value);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            if (delegate != null) delegate.visitEnum(name, descriptor, value);
            if (recorder != null) recorder.visitEnum(name, descriptor, value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            AnnotationVisitor d = delegate != null ? delegate.visitAnnotation(name, descriptor) : null;
            AnnotationVisitor r = recorder != null ? recorder.visitAnnotation(name, descriptor) : null;
            return new MultiplexAnnotationVisitor(d, r);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            AnnotationVisitor d = delegate != null ? delegate.visitArray(name) : null;
            AnnotationVisitor r = recorder != null ? recorder.visitArray(name) : null;
            return new MultiplexAnnotationVisitor(d, r);
        }

        @Override
        public void visitEnd() {
            if (delegate != null) delegate.visitEnd();
            if (recorder != null) recorder.visitEnd();
        }
    }
}
