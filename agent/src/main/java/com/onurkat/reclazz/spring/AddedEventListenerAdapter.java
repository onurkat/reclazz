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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Spring's event adapter, with a reflective delegate into an added method. */
public final class AddedEventListenerAdapter {
    static final String EVENT = "Lorg/springframework/context/event/EventListener;";
    static final String TRANSACTIONAL = "Lorg/springframework/transaction/event/TransactionalEventListener;";
    private static final String ORDER = "Lorg/springframework/core/annotation/Order;";
    private static final String BASE = "org/springframework/context/event/ApplicationListenerMethodAdapter";
    private static final String TX_BASE = "org/springframework/transaction/event/TransactionalApplicationListenerMethodAdapter";
    private static final String ACTIVE = InjectedNames.PREFIX + "active";
    private static final String TARGET = InjectedNames.PREFIX + "target";
    private static final String RESULT = InjectedNames.PREFIX + "result";
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
                    || method.visibleAnnotations.stream().noneMatch(a -> a.desc.equals(EVENT) || a.desc.equals(TRANSACTIONAL))) continue;
            Type[] args = Type.getArgumentTypes(method.desc);
            int result = Type.getReturnType(method.desc).getSort();
            String reason = null;
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0
                    || (result != Type.VOID && result != Type.OBJECT) || args.length != 1
                    || (args[0].getSort() != Type.OBJECT && args[0].getSort() != Type.ARRAY))
                reason = "only void or single event object returns on instance methods with one reference event parameter are supported";
            else if (method.signature != null)
                reason = "generic listener signatures need a restart";
            else if (transactional(method) && (result != Type.VOID
                    || method.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(EVENT))))
                reason = "transactional listeners require a void return and only the direct TransactionalEventListener annotation";
            else if (classAdvice || method.visibleAnnotations.stream().anyMatch(a ->
                    !carried(a.desc) && !a.desc.equals("Ljava/lang/Deprecated;")))
                reason = "additional advice or method annotations cannot be applied to the event delegate";
            if (reason == null) methods.add(method);
            else refused.add(method.name + method.desc + ": " + reason);
        }
        return new Plan(methods, refused);
    }

    static boolean transactional(MethodNode method) {
        return method.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(TRANSACTIONAL));
    }
    private static boolean carried(String desc) { return desc.equals(EVENT) || desc.equals(TRANSACTIONAL) || desc.equals(ORDER); }

    // Trusted lookup reader: never return the lookup or accept an arbitrary body.
    static List<Object> create(Class<?> owner, String beanName, Supplier<?> currentBean, Plan plan) throws Throwable {
        return create(owner, beanName, currentBean, plan, () -> true);
    }

    static List<Object> create(Class<?> owner, String beanName, Supplier<?> currentBean, Plan plan,
                               BooleanSupplier active) throws Throwable {
        List<Object> listeners = new ArrayList<>();
        for (boolean transactional : List.of(false, true)) {
            var methods = plan.methods().stream().filter(m -> transactional(m) == transactional).toList();
            if (!methods.isEmpty()) listeners.addAll(createGroup(owner, beanName, currentBean,
                    new Plan(methods, List.of()), active, transactional));
        }
        return listeners;
    }

    private static List<Object> createGroup(Class<?> owner, String beanName, Supplier<?> currentBean, Plan plan,
                                            BooleanSupplier active, boolean transactional) throws Throwable {
        if (plan.methods().isEmpty()) return List.of();
        MethodHandles.Lookup lookup = LookupCapture.get(owner);
        if (lookup == null) throw new IllegalStateException("no captured lookup for " + owner.getName());
        for (MethodNode method : plan.methods()) {
            Class<?> result = MethodType.fromMethodDescriptorString(method.desc, owner.getClassLoader()).returnType();
            if (result != void.class) requireSingleEvent(result);
        }
        String internal = Type.getInternalName(owner);
        String adapter = internal + (transactional ? "$$ReclazzTransactionalEvent" : "$$ReclazzEvent");
        String base = transactional ? TX_BASE : BASE;
        String supplier = "Ljava/util/function/Supplier;";
        String function = "Ljava/util/function/Function;";
        String enabled = "Ljava/util/function/BooleanSupplier;";
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, adapter, null, base, null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, TARGET, supplier, null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, RESULT, function, null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, ACTIVE, enabled, null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;Ljava/lang/reflect/Method;" + supplier + function + enabled + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "getDeclaringClass", "()Ljava/lang/Class;", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>",
                "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/reflect/Method;)V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 3);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, TARGET, supplier);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 4);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, RESULT, function);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 5);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, ACTIVE, enabled);
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

        // A transaction synchronization may still own a removed adapter.
        // Guard processing before Spring evaluates conditions or invokes code.
        for (String entry : List.of("onApplicationEvent", "processEvent")) {
            MethodVisitor guard = writer.visitMethod(Opcodes.ACC_PUBLIC, entry,
                    "(Lorg/springframework/context/ApplicationEvent;)V", null, null);
            guard.visitCode();
            guard.visitVarInsn(Opcodes.ALOAD, 0);
            guard.visitFieldInsn(Opcodes.GETFIELD, adapter, ACTIVE, enabled);
            guard.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BooleanSupplier", "getAsBoolean", "()Z", true);
            Label retired = new Label();
            guard.visitJumpInsn(Opcodes.IFEQ, retired);
            guard.visitVarInsn(Opcodes.ALOAD, 0);
            guard.visitVarInsn(Opcodes.ALOAD, 1);
            guard.visitMethodInsn(Opcodes.INVOKESPECIAL, base, entry, "(Lorg/springframework/context/ApplicationEvent;)V", false);
            guard.visitLabel(retired);
            guard.visitInsn(Opcodes.RETURN);
            guard.visitMaxs(0, 0);
            guard.visitEnd();
        }

        for (MethodNode method : plan.methods()) {
            Type result = Type.getReturnType(method.desc);
            boolean returnsEvent = result.getSort() != Type.VOID;
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
            if (returnsEvent) {
                // Validate the actual value as well: an Object return can hide
                // a collection or an async result that Spring would otherwise expand.
                mv.visitVarInsn(Opcodes.ASTORE, 3);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, adapter, RESULT, function);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, result.getInternalName());
                mv.visitInsn(Opcodes.ARETURN);
            } else mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(done);
            if (returnsEvent) mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(returnsEvent ? Opcodes.ARETURN : Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        writer.visitEnd();
        MethodHandles.Lookup hidden = lookup.defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        var constructor = hidden.findConstructor(hidden.lookupClass(),
                MethodType.methodType(void.class, String.class, Method.class, Supplier.class, Function.class, BooleanSupplier.class));
        Function<Object, Object> checkResult = result -> {
            if (result != null) requireSingleEvent(result.getClass());
            return result;
        };
        List<Object> listeners = new ArrayList<>();
        for (MethodNode method : plan.methods()) {
            Class<?> parameter = MethodType.fromMethodDescriptorString(method.desc, owner.getClassLoader()).parameterType(0);
            Method delegate = hidden.lookupClass().getDeclaredMethod(DELEGATE + method.name, parameter);
            listeners.add(constructor.invoke(beanName, delegate, currentBean, checkResult, active));
        }
        return listeners;
    }

    private static void requireSingleEvent(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || java.util.stream.BaseStream.class.isAssignableFrom(type)
                || CompletionStage.class.isAssignableFrom(type) || Future.class.isAssignableFrom(type)
                || Flow.Publisher.class.isAssignableFrom(type) || reactivePublisher(type))
            throw new IllegalArgumentException("added listener must return a single event object or null; unsupported result: " + type.getName());
    }

    private static boolean reactivePublisher(Class<?> type) {
        if (type == null) return false;
        if (type.getName().equals("org.reactivestreams.Publisher")) return true;
        for (Class<?> contract : type.getInterfaces()) if (reactivePublisher(contract)) return true;
        return reactivePublisher(type.getSuperclass());
    }
}
