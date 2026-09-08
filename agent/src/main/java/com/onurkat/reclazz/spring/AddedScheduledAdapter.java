/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import com.onurkat.reclazz.transform.SafeClassWriter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** A collectible, reflective view of scheduled methods living in a companion. */
public final class AddedScheduledAdapter {
    static final String SCHEDULED = "Lorg/springframework/scheduling/annotation/Scheduled;";
    static final String SCHEDULES = "Lorg/springframework/scheduling/annotation/Schedules;";
    private static final String TARGET = InjectedNames.PREFIX + "target";

    private AddedScheduledAdapter() { }

    record Plan(List<MethodNode> methods, List<String> refused) { }

    static Plan inspect(byte[] bytecode, Set<String> added) {
        List<MethodNode> methods = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (bytecode == null || added.isEmpty()) return new Plan(methods, refused);
        ClassNode source = new ClassNode();
        new ClassReader(bytecode).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        for (MethodNode method : source.methods) {
            if (!added.contains(method.name + ":" + method.desc) || method.visibleAnnotations == null) continue;
            if (method.visibleAnnotations.stream().noneMatch(a -> isScheduling(a.desc))) continue;
            String reason = null;
            if (!method.desc.equals("()V") || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0)
                reason = "only no-argument void instance methods are supported";
            else if (method.visibleAnnotations.stream().anyMatch(a -> !isScheduling(a.desc)
                    && !a.desc.equals("Ljava/lang/Deprecated;")))
                reason = "additional method annotations cannot be applied to the scheduled delegate";
            if (reason == null) methods.add(method);
            else refused.add(method.name + method.desc + ": " + reason);
        }
        return new Plan(methods, refused);
    }

    private static boolean isScheduling(String descriptor) {
        return descriptor.equals(SCHEDULED) || descriptor.equals(SCHEDULES);
    }

    // Deliberately package-private: the trusted lookup capability stays inside
    // the engine. No public operation returns it or defines caller-supplied code.
    static Object create(Class<?> owner, Supplier<?> currentBean, Plan plan) throws Throwable {
        if (plan.methods().isEmpty()) return null;
        MethodHandles.Lookup lookup = LookupCapture.get(owner);
        if (lookup == null) throw new IllegalStateException("no captured lookup for " + owner.getName());
        String internal = Type.getInternalName(owner);
        String adapter = internal + "$$ReclazzScheduled";
        String targetDesc = Type.getDescriptor(owner);
        String supplierDesc = "Ljava/util/function/Supplier;";
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                adapter, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, TARGET, supplierDesc, null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + supplierDesc + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, TARGET, supplierDesc);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        for (MethodNode method : plan.methods()) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method.name, "()V", null, null);
            // Spring owns cron, placeholders, time units, repeatable schedules
            // and validation. Copy only scheduling annotations, not advice.
            for (var annotation : method.visibleAnnotations)
                if (isScheduling(annotation.desc)) annotation.accept(mv.visitAnnotation(annotation.desc, true));
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, adapter, TARGET, supplierDesc);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, internal);
            mv.visitVarInsn(Opcodes.ASTORE, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            Label done = new Label();
            mv.visitJumpInsn(Opcodes.IFNULL, done);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC,
                    "com/onurkat/reclazz/bootstrap/ReclazzBootstrap", "bootstrapMethod",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)"
                            + "Ljava/lang/invoke/CallSite;", false);
            mv.visitInvokeDynamicInsn(method.name, "(" + targetDesc + ")V", bootstrap,
                    internal, CallSiteAdapter.descHash("()V"));
            mv.visitLabel(done);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        writer.visitEnd();
        MethodHandles.Lookup hidden = lookup.defineHiddenClass(writer.toByteArray(), true,
                MethodHandles.Lookup.ClassOption.NESTMATE);
        return hidden.findConstructor(hidden.lookupClass(), MethodType.methodType(void.class, Supplier.class)).invoke(currentBean);
    }
}
