/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.transform.CallSiteAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Makes a handler method added by a reload reachable to Spring's mapping scan.
 *
 * The scan reads the controller through reflection, and on a stock JDK a method
 * this reload added is not there to be read: the JVM will not accept a
 * redefinition that adds one, so it lives in the companion. The endpoint then
 * answers 404 until the server is restarted, which is the one limitation people
 * hit while writing a new endpoint, the moment they most want a reload.
 *
 * So the method is given somewhere to be read from: a small class holding the
 * controller, carrying copies of the added methods with their annotations and
 * signatures intact, each body doing nothing but calling through to the
 * implementation. Spring maps that class the way it maps any handler, and the
 * request lands in the code that was just written.
 *
 * The copies are made from the compiled controller, so whatever the developer
 * wrote is what Spring sees: the mapping annotation, the parameter annotations,
 * the return type. Nothing here interprets them.
 */
public final class AddedEndpointAdapter {

    private static final String BOOTSTRAP =
            "com/onurkat/reclazz/bootstrap/ReclazzBootstrap";

    /** Enough to tell a handler method from an ordinary one. */
    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            "Lorg/springframework/web/bind/annotation/RequestMapping;",
            "Lorg/springframework/web/bind/annotation/GetMapping;",
            "Lorg/springframework/web/bind/annotation/PostMapping;",
            "Lorg/springframework/web/bind/annotation/PutMapping;",
            "Lorg/springframework/web/bind/annotation/DeleteMapping;",
            "Lorg/springframework/web/bind/annotation/PatchMapping;");

    private AddedEndpointAdapter() {
    }

    /**
     * @return the adapter instance holding {@code controllerBean}, or null when
     *         this reload added no handler methods and there is nothing to map
     */
    public static Object create(Class<?> controllerClass,
                                Object controllerBean,
                                byte[] newBytecode,
                                Set<String> addedMethods,
                                int version) throws Throwable {
        ClassNode source = new ClassNode();
        new ClassReader(newBytecode).accept(source, 0);

        List<MethodNode> handlers = new ArrayList<>();
        for (MethodNode method : source.methods) {
            if (!addedMethods.contains(method.name + ":" + method.desc)) continue;
            if (isHandler(method)) handlers.add(method);
        }
        if (handlers.isEmpty()) return null;

        String controllerInternal = Type.getInternalName(controllerClass);
        String adapterInternal = controllerInternal
                + SpringMvcReloader.ADAPTER_SUFFIX.replace('.', '/') + version;
        byte[] bytes = generate(adapterInternal, controllerInternal, handlers, source);

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                controllerClass, MethodHandles.lookup());
        Class<?> adapterClass = lookup.defineClass(bytes);
        return adapterClass.getConstructor(controllerClass).newInstance(controllerBean);
    }

    private static boolean isHandler(MethodNode method) {
        if (method.visibleAnnotations == null) return false;
        for (var annotation : method.visibleAnnotations) {
            if (MAPPING_ANNOTATIONS.contains(annotation.desc)) return true;
        }
        return false;
    }

    private static byte[] generate(String adapterInternal, String controllerInternal,
                                   List<MethodNode> handlers, ClassNode source) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                adapterInternal, null, "java/lang/Object", null);

        // The controller's own annotations come along, because the mapping is
        // not built from the method alone: a class-level @RequestMapping is the
        // prefix of every path below it, and @RestController is what makes the
        // return value a response body. Without them the copy was mapped at the
        // wrong path and answered nowhere.
        if (source.visibleAnnotations != null) {
            for (var a : source.visibleAnnotations) a.accept(writer.visitAnnotation(a.desc, true));
        }
        if (source.invisibleAnnotations != null) {
            for (var a : source.invisibleAnnotations) a.accept(writer.visitAnnotation(a.desc, false));
        }

        String targetDesc = "L" + controllerInternal + ";";
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "target", targetDesc, null, null).visitEnd();

        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(" + targetDesc + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapterInternal, "target", targetDesc);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        for (MethodNode handler : handlers) {
            writeDelegate(writer, adapterInternal, controllerInternal, targetDesc, handler);
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeDelegate(ClassWriter writer, String adapterInternal,
                                      String controllerInternal, String targetDesc,
                                      MethodNode handler) {
        var mv = writer.visitMethod(Opcodes.ACC_PUBLIC, handler.name, handler.desc, null,
                handler.exceptions == null ? null : handler.exceptions.toArray(new String[0]));

        // The annotations are the whole point: Spring reads them here to build
        // the mapping, so they are copied exactly as the developer wrote them.
        if (handler.visibleAnnotations != null) {
            for (var a : handler.visibleAnnotations) a.accept(mv.visitAnnotation(a.desc, true));
        }
        if (handler.invisibleAnnotations != null) {
            for (var a : handler.invisibleAnnotations) a.accept(mv.visitAnnotation(a.desc, false));
        }
        copyParameterAnnotations(mv, handler);

        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, adapterInternal, "target", targetDesc);

        Type[] args = Type.getArgumentTypes(handler.desc);
        int slot = 1;
        for (Type arg : args) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }

        // The same call site the controller's own code would make, so it
        // resolves through the dispatch table to the current implementation and
        // keeps following it across later reloads.
        String indyDesc = "(" + targetDesc + handler.desc.substring(1);
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, BOOTSTRAP, "bootstrapMethod",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn(handler.name, indyDesc, bsm,
                controllerInternal, CallSiteAdapter.descHash(handler.desc));

        mv.visitInsn(Type.getReturnType(handler.desc).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void copyParameterAnnotations(org.objectweb.asm.MethodVisitor mv,
                                                 MethodNode handler) {
        if (handler.visibleParameterAnnotations != null) {
            for (int i = 0; i < handler.visibleParameterAnnotations.length; i++) {
                var list = handler.visibleParameterAnnotations[i];
                if (list == null) continue;
                for (var a : list) a.accept(mv.visitParameterAnnotation(i, a.desc, true));
            }
        }
        if (handler.invisibleParameterAnnotations != null) {
            for (int i = 0; i < handler.invisibleParameterAnnotations.length; i++) {
                var list = handler.invisibleParameterAnnotations[i];
                if (list == null) continue;
                for (var a : list) a.accept(mv.visitParameterAnnotation(i, a.desc, false));
            }
        }
    }
}
