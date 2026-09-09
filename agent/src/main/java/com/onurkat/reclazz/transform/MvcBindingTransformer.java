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

/** Preserve Spring's binding/model lifecycle, adapting only method discovery and receivers. */
public final class MvcBindingTransformer implements ClassFileTransformer {
    public static final String TARGET = "org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter";
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/MvcBindingBridge";
    private static final String INTROSPECTOR = "org/springframework/core/MethodIntrospector";
    private static final String SELECT = "(Ljava/lang/Class;Lorg/springframework/util/ReflectionUtils$MethodFilter;)Ljava/util/Set;";
    private static final String INVOCABLE = "org/springframework/web/method/support/InvocableHandlerMethod";
    private static final String INVOCABLE_CTOR = "(Ljava/lang/Object;Ljava/lang/reflect/Method;)V";

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
            int[] sites = {0, 0, 0};
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
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            if (opcode == INVOKESTATIC && owner.equals(INTROSPECTOR)
                                    && name.equals("selectMethods") && descriptor.equals(SELECT)) {
                                int filter = newLocal(Type.getObjectType("org/springframework/util/ReflectionUtils$MethodFilter"));
                                storeLocal(filter);
                                super.visitMethodInsn(INVOKESTATIC, BRIDGE, "metadataClass", "(Ljava/lang/Class;)Ljava/lang/Class;", false);
                                loadLocal(filter);
                                sites[0]++;
                            } else if (opcode == INVOKESPECIAL && name.equals("<init>")
                                    && owner.equals(INVOCABLE) && descriptor.equals(INVOCABLE_CTOR)) {
                                int selected = newLocal(Type.getType(java.lang.reflect.Method.class));
                                storeLocal(selected); loadLocal(selected);
                                super.visitMethodInsn(INVOKESTATIC, BRIDGE, "receiver",
                                        "(Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/Object;", false);
                                loadLocal(selected);
                                sites[1]++;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                            if ((method.equals("getModelFactory") || method.equals("getDataBinderFactory"))
                                    && opcode == INVOKEVIRTUAL && owner.equals("org/springframework/web/method/HandlerMethod")
                                    && name.equals("getBeanType") && descriptor.equals("()Ljava/lang/Class;")) {
                                super.visitMethodInsn(INVOKESTATIC, BRIDGE, "controllerClass", "(Ljava/lang/Class;)Ljava/lang/Class;", false);
                                sites[2]++;
                            }
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            if (sites[0] != 4 || sites[1] != 2 || sites[2] != 2)
                throw new IllegalStateException("unsupported MVC binding/model sites: " + sites[0] + "/" + sites[1] + "/" + sites[2]);
            return writer.toByteArray();
        } catch (Throwable failure) {
            StatusReporter.warn("Added MVC binding/model hook unavailable: " + com.onurkat.reclazz.ui.Failures.describe(failure));
            return null;
        }
    }
}
