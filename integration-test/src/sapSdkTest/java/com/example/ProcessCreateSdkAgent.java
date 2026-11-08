/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import java.lang.instrument.*;
import org.objectweb.asm.*;

/** Test-only empty-DB seam on the exact native provider class; never ships in agent artifacts. */
public final class ProcessCreateSdkAgent {
    public static void premain(String options, Instrumentation instrumentation) {
        ProcessObserverAgent.premain("observed", instrumentation);
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    java.security.ProtectionDomain domain, byte[] bytes) {
                if (!"de/hybris/platform/processengine/definition/ProcessDefinitionsProvider".equals(name)) return null;
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    boolean found;
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                        if (method.equals("getLatestDefinitionIdFor") && descriptor.equals("(Lde/hybris/platform/processengine/definition/ProcessDefinitionId;)Lde/hybris/platform/processengine/definition/ProcessDefinitionId;")) {
                            found = true;
                            var mv = super.visitMethod(access,method,descriptor,signature,exceptions);
                            mv.visitCode(); mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(1,2); mv.visitEnd();
                            return null;
                        }
                        return super.visitMethod(access,method,descriptor,signature,exceptions);
                    }
                    @Override public void visitEnd() {
                        if (!found) throw new IllegalStateException("Native provider query seam missing");
                        super.visitEnd();
                    }
                },0);
                System.out.println("SDK_CREATE_EMPTY_DB_SEAM exact native provider, latest-ID query only; no tenant/DB/task execution");
                return writer.toByteArray();
            }
        },false);
    }
}
