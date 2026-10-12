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

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!MAPPING.equals(name) && !SERVLET.equals(name)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
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
            if (sites[0] != 2 || sites[1] != (mapping ? 0 : 2)) throw new IllegalStateException("unsupported sites " + sites[0] + "/" + sites[1]);
            byte[] result = writer.toByteArray();
            MvcSingletonBridge.installed(loader, mapping ? 1 : 2);
            return result;
        } catch (Throwable failure) {
            StatusReporter.warn("MVC singleton routing unavailable: " + com.onurkat.reclazz.ui.Failures.describe(failure));
            return null;
        }
    }
}
