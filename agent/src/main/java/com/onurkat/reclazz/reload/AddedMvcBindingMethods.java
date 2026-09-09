/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.DispatchTable;
import com.onurkat.reclazz.bootstrap.MvcBindingBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;

/** Full saved binding/model metadata, with bodies delegated to the actual controller/advice. */
final class AddedMvcBindingMethods {
    private static final String TARGET_FIELD = InjectedNames.PREFIX + "target";
    private static final String INVOKERS_FIELD = InjectedNames.PREFIX + "invokers";
    private static final String BINDER = "Lorg/springframework/web/bind/annotation/InitBinder;";
    private static final String MODEL = "Lorg/springframework/web/bind/annotation/ModelAttribute;";
    private static final Set<String> METHOD_ANNOTATIONS = Set.of(BINDER, MODEL, "Ljava/lang/Deprecated;");
    private static final Set<String> MAPPINGS = Set.of("RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping");
    private static final Set<String> CLASS_ANNOTATIONS = Set.of(
            "Lorg/springframework/stereotype/Controller;", "Lorg/springframework/stereotype/Component;",
            "Lorg/springframework/web/bind/annotation/RestController;",
            "Lorg/springframework/web/bind/annotation/ControllerAdvice;",
            "Lorg/springframework/web/bind/annotation/RestControllerAdvice;",
            "Lorg/springframework/web/bind/annotation/RequestMapping;",
            "Lorg/springframework/web/bind/annotation/ResponseBody;",
            "Lorg/springframework/web/bind/annotation/ResponseStatus;",
            "Lorg/springframework/core/annotation/Order;", "Ljava/lang/Deprecated;");
    private AddedMvcBindingMethods() { }

    static Set<String> publish(Class<?> owner, byte[] bytes, MethodHandles.Lookup lookup,
                               Map<String, MethodHandle> targets) throws Throwable {
        var source = new ClassNode();
        new ClassReader(bytes).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        Set<String> original = new HashSet<>();
        for (Method m : owner.getDeclaredMethods()) original.add(m.getName() + Type.getMethodDescriptor(m));
        List<MethodNode> handlers = source.methods.stream().filter(AddedMvcBindingMethods::isCallback).toList();
        Set<String> added = new LinkedHashSet<>();
        for (var m : handlers) if (!original.contains(m.name + m.desc)) added.add(m.name + m.desc);
        if (added.isEmpty()) {
            if (MvcBindingBridge.wasAdapted(owner)) MvcBindingBridge.replace(owner, null, null, null);
            return Set.of();
        }
        try {
            Class<?> resolver = Class.forName("org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter", false, owner.getClassLoader());
            if (!MvcBindingBridge.isHooked(resolver)) throw new IllegalStateException("MVC binding/model hook is not active; start with -javaagent");
            if (owner.getSuperclass() != Object.class || !source.interfaces.isEmpty() || source.signature != null)
                throw new IllegalStateException("inherited/interface/type-variable binding/model metadata");
            if (source.visibleAnnotations != null) for (var a : source.visibleAnnotations)
                if (!CLASS_ANNOTATIONS.contains(a.desc)) throw new IllegalStateException("unsupported class annotation " + a.desc);
            for (var m : handlers) {
                if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0
                        || hasTypeVariables(m.signature))
                    throw new IllegalStateException("unsupported handler shape " + m.name);
                for (var a : m.visibleAnnotations) if (!METHOD_ANNOTATIONS.contains(a.desc))
                    throw new IllegalStateException("unsupported handler annotation " + a.desc);
            }
            String internal = source.name + "$__reclazz$MvcBindingMethods";
            String targetDesc = Type.getDescriptor(owner);
            var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
                    internal, null, "java/lang/Object", null);
            if (source.visibleAnnotations != null) for (var a : source.visibleAnnotations)
                a.accept(writer.visitAnnotation(a.desc, true));
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, TARGET_FIELD, targetDesc, null, null).visitEnd();
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, INVOKERS_FIELD, "[Ljava/lang/invoke/MethodHandle;", null, null).visitEnd();
            var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;[Ljava/lang/invoke/MethodHandle;)V", null, null);
            ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ALOAD, 1);
            ctor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(owner));
            ctor.visitFieldInsn(Opcodes.PUTFIELD, internal, TARGET_FIELD, targetDesc);
            ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ALOAD, 2);
            ctor.visitFieldInsn(Opcodes.PUTFIELD, internal, INVOKERS_FIELD, "[Ljava/lang/invoke/MethodHandle;");
            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
            List<MethodHandle> invokers = new ArrayList<>();
            for (int i = 0; i < handlers.size(); i++) {
                MethodNode m = handlers.get(i);
                String key = InjectedNames.siteKey(m.name, InjectedNames.descHash(m.desc));
                MethodHandle target = targets.get(key);
                if (target == null) throw new IllegalStateException("missing handler target " + m.name);
                invokers.add(DispatchTable.getOrCreate(owner).getOrCreateMethodSite(key, new MutableCallSite(target)).dynamicInvoker());
                var mv = writer.visitMethod(Opcodes.ACC_PUBLIC, m.name, m.desc, m.signature, m.exceptions.toArray(String[]::new));
                for (var a : m.visibleAnnotations) a.accept(mv.visitAnnotation(a.desc, true));
                if (m.parameters != null) for (var p : m.parameters) mv.visitParameter(p.name, p.access);
                if (m.visibleParameterAnnotations != null) for (int p = 0; p < m.visibleParameterAnnotations.length; p++)
                    if (m.visibleParameterAnnotations[p] != null) for (var a : m.visibleParameterAnnotations[p])
                        a.accept(mv.visitParameterAnnotation(p, a.desc, true));
                mv.visitCode(); mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, internal, INVOKERS_FIELD, "[Ljava/lang/invoke/MethodHandle;");
                mv.visitLdcInsn(i); mv.visitInsn(Opcodes.AALOAD);
                mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitFieldInsn(Opcodes.GETFIELD, internal, TARGET_FIELD, targetDesc);
                int slot = 1;
                for (Type arg : Type.getArgumentTypes(m.desc)) { mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot); slot += arg.getSize(); }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "(" + targetDesc + m.desc.substring(1), false);
                mv.visitInsn(Type.getReturnType(m.desc).getOpcode(Opcodes.IRETURN)); mv.visitMaxs(0, 0); mv.visitEnd();
            }
            writer.visitEnd();
            var hidden = lookup.defineHiddenClass(writer.toByteArray(), false, MethodHandles.Lookup.ClassOption.NESTMATE);
            Class<?> schema = hidden.lookupClass();
            for (Method m : schema.getDeclaredMethods()) if (async(m.getReturnType()))
                throw new IllegalStateException("asynchronous binding/model method " + m.getName());
            for (Method m : schema.getDeclaredMethods()) {
                if (m.getReturnType() != void.class && Arrays.stream(m.getDeclaredAnnotations())
                        .anyMatch(a -> a.annotationType().getName().equals("org.springframework.web.bind.annotation.InitBinder")))
                    throw new IllegalStateException("InitBinder method must return void: " + m.getName());
            }
            MethodHandle factory = hidden.findConstructor(schema, MethodType.methodType(void.class, Object.class, MethodHandle[].class))
                    .asType(MethodType.methodType(Object.class, Object.class, MethodHandle[].class));
            MvcBindingBridge.replace(owner, schema, factory, invokers.toArray(MethodHandle[]::new));
            return Set.copyOf(added);
        } catch (Throwable failure) {
            MvcBindingBridge.replace(owner, null, null, null);
            throw failure;
        }
    }
    private static boolean isCallback(MethodNode method) {
        if (method.visibleAnnotations == null) return false;
        boolean model = false, mapped = false;
        for (var a : method.visibleAnnotations) {
            if (a.desc.equals(BINDER)) return true;
            if (a.desc.equals(MODEL)) model = true;
            for (String mapping : MAPPINGS)
                if (a.desc.equals("Lorg/springframework/web/bind/annotation/" + mapping + ";")) mapped = true;
        }
        // Spring's MODEL_ATTRIBUTE_METHODS excludes request-mapped methods.
        return model && !mapped;
    }
    private static boolean async(Class<?> type) {
        if (type == null) return false;
        if (java.util.concurrent.Future.class.isAssignableFrom(type)
                || java.util.concurrent.CompletionStage.class.isAssignableFrom(type)
                || java.util.concurrent.Callable.class.isAssignableFrom(type)
                || type.getName().equals("org.reactivestreams.Publisher")
                || type.getName().equals("org.springframework.web.context.request.async.DeferredResult")
                || type.getName().equals("org.springframework.web.context.request.async.WebAsyncTask")
                || type.getName().equals("org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter")
                || type.getName().equals("org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody")
                || type == java.util.concurrent.Flow.Publisher.class) return true;
        for (Class<?> parent : type.getInterfaces()) if (async(parent)) return true;
        return async(type.getSuperclass());
    }
    private static boolean hasTypeVariables(String signature) {
        if (signature == null) return false;
        boolean[] found = {false};
        new org.objectweb.asm.signature.SignatureReader(signature).accept(
                new org.objectweb.asm.signature.SignatureVisitor(Opcodes.ASM9) {
                    @Override public void visitFormalTypeParameter(String name) { found[0] = true; }
                    @Override public void visitTypeVariable(String name) { found[0] = true; }
                });
        return found[0];
    }
}
