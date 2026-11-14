/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.MvcSingletonBridge;
import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Native Spring5/6 selection stays intact; only selected singleton identities are substituted. */
public final class MvcSingletonTransformer implements ClassFileTransformer {
    public static final String MAPPING = "org/springframework/web/servlet/handler/AbstractHandlerMapping";
    public static final String SERVLET = "org/springframework/web/servlet/DispatcherServlet";
    private static final String CHAIN = "org/springframework/web/servlet/HandlerExecutionChain";
    private static final String INTERCEPTOR = "org/springframework/web/servlet/HandlerInterceptor";
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/MvcSingletonBridge";

    private static int mappingProfile(ClassReader reader) {
        var shape = new java.util.ArrayList<String>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc,
                                                       String signature, String[] exceptions) {
                if (!method.equals("getHandlerExecutionChain")
                        || !(desc.equals("(Ljava/lang/Object;Ljavax/servlet/http/HttpServletRequest;)L" + CHAIN + ";")
                        || desc.equals("(Ljava/lang/Object;Ljakarta/servlet/http/HttpServletRequest;)L" + CHAIN + ";"))) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (type.contains("/ApiVersion") || type.contains("$ApiVersion"))
                            shape.add(opcode + ":" + type);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                        if (descriptor.contains("/ApiVersion") || field.equals("API_VERSION_ATTRIBUTE"))
                            shape.add(opcode + ":" + owner + "." + field + descriptor);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                        if (method.equals("addInterceptor") || owner.contains("/ApiVersion")
                                || owner.contains("$ApiVersion") || descriptor.contains("/ApiVersion"))
                            shape.add(opcode + ":" + owner + "." + method + descriptor + ":" + itf);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String add = Opcodes.INVOKEVIRTUAL + ":" + CHAIN + ".addInterceptor(L" + INTERCEPTOR + ";)V:false";
        if (shape.equals(java.util.List.of(add, add))) return 2;
        String strategy = "Lorg/springframework/web/accept/ApiVersionStrategy;";
        String holder = "org/springframework/web/accept/ApiVersionHolder";
        String interceptor = MAPPING + "$ApiVersionDeprecationHandlerInterceptor";
        String field = Opcodes.GETFIELD + ":" + MAPPING + ".versionStrategy" + strategy;
        var seven = java.util.List.of(add, add, field,
                Opcodes.GETSTATIC + ":" + MAPPING + ".API_VERSION_ATTRIBUTELjava/lang/String;",
                Opcodes.CHECKCAST + ":" + holder,
                Opcodes.INVOKEVIRTUAL + ":" + holder + ".hasVersion()Z:false",
                Opcodes.INVOKEVIRTUAL + ":" + holder + ".getVersion()Ljava/lang/Comparable;:false",
                Opcodes.NEW + ":" + interceptor, field,
                Opcodes.INVOKESPECIAL + ":" + interceptor + ".<init>(" + strategy + "Ljava/lang/Comparable;)V:false", add);
        if (shape.equals(seven)) return 3;
        throw new IllegalStateException("unsupported interceptor profile");
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!MAPPING.equals(name) && !SERVLET.equals(name)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            int mappingSites = MAPPING.equals(name) ? mappingProfile(reader) : 2;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] sites = {0, 0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc,
                                                           String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, method, desc, signature, exceptions);
                    boolean chain = MAPPING.equals(name) && method.equals("getHandlerExecutionChain")
                            && (desc.equals("(Ljava/lang/Object;Ljavax/servlet/http/HttpServletRequest;)L" + CHAIN + ";")
                                || desc.equals("(Ljava/lang/Object;Ljakarta/servlet/http/HttpServletRequest;)L" + CHAIN + ";"));
                    boolean view = method.equals("getHandlerMappings") && desc.equals("()Ljava/util/List;");
                    boolean dispatch = method.equals("getHandler")
                            && (desc.equals("(Ljavax/servlet/http/HttpServletRequest;)L" + CHAIN + ";")
                                || desc.equals("(Ljakarta/servlet/http/HttpServletRequest;)L" + CHAIN + ";"));
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean itf) {
                            if (chain && opcode == Opcodes.INVOKEVIRTUAL && !itf && owner.equals(CHAIN)
                                    && called.equals("addInterceptor") && descriptor.equals("(L" + INTERCEPTOR + ";)V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "current", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, INTERCEPTOR);
                                sites[0]++;
                            }
                            super.visitMethodInsn(opcode, owner, called, descriptor, itf);
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                            super.visitFieldInsn(opcode, owner, field, descriptor);
                            if (SERVLET.equals(name) && (view || dispatch) && opcode == Opcodes.GETFIELD
                                    && owner.equals(SERVLET) && field.equals("handlerMappings") && descriptor.equals("Ljava/util/List;")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "mappings", "(Ljava/util/List;)Ljava/util/List;", false);
                                sites[view ? 0 : 1]++;
                            }
                        }
                    };
                }
            }, 0);
            boolean mapping = MAPPING.equals(name);
            if (sites[0] != mappingSites || sites[1] != (mapping ? 0 : 2)) throw new IllegalStateException("unsupported sites " + sites[0] + "/" + sites[1]);
            byte[] result = writer.toByteArray();
            MvcSingletonBridge.installed(loader, mapping ? 1 : 2);
            return result;
        } catch (Throwable failure) {
            StatusReporter.warn("MVC singleton routing unavailable: " + com.onurkat.reclazz.ui.Failures.describe(failure));
            return null;
        }
    }
}
