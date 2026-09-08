/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import com.onurkat.reclazz.transform.SafeClassWriter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Spring's event adapter, with a reflective delegate into an added method. */
public final class AddedEventListenerAdapter {
    static final String EVENT = "Lorg/springframework/context/event/EventListener;";
    private static final String ORDER = "Lorg/springframework/core/annotation/Order;";
    private static final String BASE = "org/springframework/context/event/ApplicationListenerMethodAdapter";
    private static final String TARGET = InjectedNames.PREFIX + "target";
    private static final String DELEGATE = InjectedNames.PREFIX + "event$";

    private AddedEventListenerAdapter() { }

    record Plan(List<MethodNode> methods, List<String> refused) { }

    static Plan inspect(byte[] bytes, Set<String> added) {
        List<MethodNode> methods = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (bytes == null || added.isEmpty()) return new Plan(methods, refused);
        ClassNode source = new ClassNode();
        // Keep debug parameter names for conditions compiled without -parameters.
        new ClassReader(bytes).accept(source, ClassReader.SKIP_FRAMES);
        boolean classAdvice = source.visibleAnnotations != null && source.visibleAnnotations.stream().anyMatch(a ->
                a.desc.equals("Lorg/springframework/scheduling/annotation/Async;")
                || a.desc.equals("Lorg/springframework/transaction/annotation/Transactional;"));
        for (MethodNode method : source.methods) {
            if (!added.contains(method.name + ":" + method.desc) || method.visibleAnnotations == null
                    || method.visibleAnnotations.stream().noneMatch(a -> a.desc.equals(EVENT))) continue;
            Type[] args = Type.getArgumentTypes(method.desc);
            String reason = null;
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0
                    || Type.getReturnType(method.desc).getSort() != Type.VOID || args.length != 1
                    || (args[0].getSort() != Type.OBJECT && args[0].getSort() != Type.ARRAY))
                reason = "only void instance methods with one reference event parameter are supported";
            else if (method.signature != null)
                reason = "generic listener signatures need a restart";
            else if (classAdvice || method.visibleAnnotations.stream().anyMatch(a ->
                    !carried(a.desc) && !a.desc.equals("Ljava/lang/Deprecated;")))
                reason = "additional advice or method annotations cannot be applied to the event delegate";
            if (reason == null) methods.add(method);
            else refused.add(method.name + method.desc + ": " + reason);
        }
        return new Plan(methods, refused);
    }

    private static boolean carried(String desc) { return desc.equals(EVENT) || desc.equals(ORDER); }

    // Trusted lookup reader: never return the lookup or accept an arbitrary body.
    static List<Object> create(Class<?> owner, String beanName, Supplier<?> currentBean, Plan plan) throws Throwable {
        if (plan.methods().isEmpty()) return List.of();
        MethodHandles.Lookup lookup = LookupCapture.get(owner);
        if (lookup == null) throw new IllegalStateException("no captured lookup for " + owner.getName());
        String internal = Type.getInternalName(owner);
        String adapter = internal + "$$ReclazzEvent";
        String supplier = "Ljava/util/function/Supplier;";
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, adapter, null, BASE, null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, TARGET, supplier, null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;Ljava/lang/reflect/Method;" + supplier + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "getDeclaringClass", "()Ljava/lang/Class;", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, BASE, "<init>",
                "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/reflect/Method;)V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 3);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, TARGET, supplier);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // Spring invokes the annotated delegate on this adapter. That delegate
        // obtains the real, current bean; it never captures a destroyed instance.
        MethodVisitor target = writer.visitMethod(Opcodes.ACC_PROTECTED, "getTargetBean", "()Ljava/lang/Object;", null, null);
        target.visitCode();
        target.visitVarInsn(Opcodes.ALOAD, 0);
        target.visitInsn(Opcodes.ARETURN);
        target.visitMaxs(0, 0);
        target.visitEnd();

        for (MethodNode method : plan.methods()) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, DELEGATE + method.name, method.desc, null, null);
            if (method.parameters != null && !method.parameters.isEmpty()) {
                var parameter = method.parameters.get(0);
                mv.visitParameter(parameter.name, parameter.access);
            } else if (method.localVariables != null) {
                method.localVariables.stream().filter(v -> v.index == 1).findFirst()
                        .ifPresent(v -> mv.visitParameter(v.name, 0));
            }
            for (var annotation : method.visibleAnnotations)
                if (carried(annotation.desc)) annotation.accept(mv.visitAnnotation(annotation.desc, true));
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, adapter, TARGET, supplier);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, internal);
            mv.visitVarInsn(Opcodes.ASTORE, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            Label done = new Label();
            mv.visitJumpInsn(Opcodes.IFNULL, done);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC,
                    "com/onurkat/reclazz/bootstrap/ReclazzBootstrap", "bootstrapMethod",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);
            mv.visitInvokeDynamicInsn(method.name, "(" + Type.getDescriptor(owner) + method.desc.substring(1),
                    bootstrap, internal, CallSiteAdapter.descHash(method.desc));
            mv.visitLabel(done);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        writer.visitEnd();
        MethodHandles.Lookup hidden = lookup.defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        var constructor = hidden.findConstructor(hidden.lookupClass(),
                MethodType.methodType(void.class, String.class, Method.class, Supplier.class));
        List<Object> listeners = new ArrayList<>();
        for (MethodNode method : plan.methods()) {
            Class<?> parameter = MethodType.fromMethodDescriptorString(method.desc, owner.getClassLoader()).parameterType(0);
            Method delegate = hidden.lookupClass().getDeclaredMethod(DELEGATE + method.name, parameter);
            listeners.add(constructor.invoke(beanName, delegate, currentBean));
        }
        return listeners;
    }
}
