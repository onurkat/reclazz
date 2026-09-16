/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.RequestGate;
import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** A finally block around FrameworkServlet.processRequest, for javax and jakarta MVC. */
public final class RequestBoundaryTransformer implements ClassFileTransformer {
    public static final String TARGET = "org/springframework/web/servlet/FrameworkServlet";
    private static InsnList async(String operation, int slot, String namespace) {
        String bridge = "com/onurkat/reclazz/bootstrap/AsyncMvcBoundary";
        InsnList code = new InsnList();
        if (operation.equals("enter")) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new LdcInsnNode(Type.getObjectType(TARGET)));
            code.add(new LdcInsnNode(namespace));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "enter",
                    "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false));
            code.add(new VarInsnNode(Opcodes.ASTORE, slot));
        } else {
            code.add(new VarInsnNode(Opcodes.ALOAD, slot));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "exit", "(Ljava/lang/Object;)V", false));
        }
        return code;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                            ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() { return loader; }
            };
            ClassNode type = new ClassNode(Opcodes.ASM9);
            reader.accept(type, ClassReader.EXPAND_FRAMES);
            boolean found = false;
            for (MethodNode method : type.methods) {
                boolean servlet = method.desc.equals("(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V")
                        || method.desc.equals("(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V");
                if (!method.name.equals("processRequest") || !servlet) continue;
                found = true;
                String namespace = method.desc.startsWith("(Ljavax/") ? "javax" : "jakarta";
                int frame = method.maxLocals++;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        method.instructions.insertBefore(instruction, async("exit", frame, namespace));
                    }
                }
                LabelNode start = new LabelNode();
                InsnList entry = async("enter", frame, namespace);
                entry.add(start);
                method.instructions.insert(entry);
                LabelNode end = new LabelNode();
                method.instructions.add(end);
                method.instructions.add(async("exit", frame, namespace));
                method.instructions.add(new InsnNode(Opcodes.ATHROW));
                // Preserve the original handlers' priority, including Spring's own finally blocks.
                method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, end, null));
            }
            if (!found) throw new IllegalStateException("Unsupported FrameworkServlet.processRequest signature");
            type.accept(writer);
            byte[] result = writer.toByteArray();
            RequestGate.global().installed();
            StatusReporter.info("Request boundary installed for MVC dispatches and supported javax/Jakarta async requests");
            return result;
        } catch (Throwable failure) {
            RequestGate.global().unavailable("Spring MVC request hook failed: " + failure.getClass().getSimpleName());
            StatusReporter.error("Request boundary could not be installed; class reloads will remain deferred.");
            return null;
        }
    }
}
