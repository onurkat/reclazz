/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Preserve Spring's exception selection, adapting only metadata and the chosen receiver. */
public final class ExceptionHandlerTransformer implements ClassFileTransformer {
    public static final String TARGET = "org/springframework/web/servlet/mvc/method/annotation/ExceptionHandlerExceptionResolver";
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/ExceptionHandlerBridge";
    private static final String METHOD_RESOLVER = "org/springframework/web/method/annotation/ExceptionHandlerMethodResolver";
    private static final String INVOCABLE = "org/springframework/web/servlet/mvc/method/annotation/ServletInvocableHandlerMethod";
    private static final String INVOCABLE_CTOR = "(Ljava/lang/Object;Ljava/lang/reflect/Method;Lorg/springframework/context/MessageSource;)V";
    private static final String HANDLER_METHOD = "(Lorg/springframework/web/method/HandlerMethod;Ljava/lang/Exception;)L" + INVOCABLE + ";";
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle RESOLVER_CTOR = new Handle(Opcodes.H_NEWINVOKESPECIAL,
            METHOD_RESOLVER, "<init>", "(Ljava/lang/Class;)V", false);

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        try {
            var reader = new ClassReader(bytes);
            var writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() {
                    return loader == null ? super.getClassLoader() : loader;
                }
            };
            int[] sites = {0, 0, 0, 0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc,
                                                           String signature, String[] exceptions) {
                    return new AdviceAdapter(Opcodes.ASM9, super.visitMethod(access, method, desc, signature, exceptions),
                            access, method, desc) {
                        @Override protected void onMethodExit(int opcode) {
                            if (method.equals("<init>") && opcode == RETURN) {
                                visitLdcInsn(Type.getObjectType(TARGET));
                                super.visitMethodInsn(INVOKESTATIC, BRIDGE, "markHooked", "(Ljava/lang/Class;)V", false);
                            }
                        }
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                                                     Handle bootstrap, Object... arguments) {
                            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
                            if (method.equals("getExceptionHandlerMethod") && desc.equals(HANDLER_METHOD)
                                    && name.equals("apply") && descriptor.equals("()Ljava/util/function/Function;")
                                    && bootstrap.equals(METAFACTORY) && arguments.length == 3
                                    && Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;").equals(arguments[0])
                                    && RESOLVER_CTOR.equals(arguments[1])
                                    && Type.getMethodType("(Ljava/lang/Class;)L" + METHOD_RESOLVER + ";").equals(arguments[2])) {
                                super.visitMethodInsn(INVOKESTATIC, BRIDGE, "resolverFactory",
                                        "(Ljava/util/function/Function;)Ljava/util/function/Function;", false);
                                sites[3]++;
                            }
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            if (opcode == INVOKESPECIAL && name.equals("<init>")) {
                                if (owner.equals(METHOD_RESOLVER) && descriptor.equals("(Ljava/lang/Class;)V")) {
                                    super.visitMethodInsn(INVOKESTATIC, BRIDGE, "metadataClass", "(Ljava/lang/Class;)Ljava/lang/Class;", false);
                                    sites[0]++;
                                } else if (owner.equals(INVOCABLE) && descriptor.equals(INVOCABLE_CTOR)) {
                                    int context = newLocal(Type.getObjectType("org/springframework/context/MessageSource"));
                                    int selected = newLocal(Type.getType(java.lang.reflect.Method.class));
                                    storeLocal(context); storeLocal(selected);
                                    loadLocal(selected);
                                    super.visitMethodInsn(INVOKESTATIC, BRIDGE, "receiver",
                                            "(Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/Object;", false);
                                    loadLocal(selected); loadLocal(context);
                                    sites[1]++;
                                }
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                            if (opcode == INVOKEVIRTUAL && owner.equals("org/springframework/web/method/HandlerMethod")
                                    && name.equals("getBeanType") && descriptor.equals("()Ljava/lang/Class;")) {
                                super.visitMethodInsn(INVOKESTATIC, BRIDGE, "controllerClass", "(Ljava/lang/Class;)Ljava/lang/Class;", false);
                                sites[2]++;
                            }
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            boolean knownConstruction = (sites[0] == 2 && sites[3] == 0)
                    || (sites[0] == 1 && sites[3] == 1);
            if (!knownConstruction || sites[1] != 2 || sites[2] != 1)
                throw new IllegalStateException("unsupported MVC resolver sites: " + sites[0] + "/" + sites[1] + "/" + sites[2]
                        + "; constructor factories: " + sites[3]);
            return writer.toByteArray();
        } catch (Throwable failure) {
            StatusReporter.warn("Added exception-handler hook unavailable: " + com.onurkat.reclazz.ui.Failures.describe(failure));
            return null;
        }
    }
}
