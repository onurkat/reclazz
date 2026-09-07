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
    private static final String GATE = "com/onurkat/reclazz/bootstrap/RequestGate";

    private static InsnList gate(String operation) {
        InsnList instructions = new InsnList();
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GATE, "global", "()L" + GATE + ";", false));
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GATE, operation, "()V", false));
        return instructions;
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
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        method.instructions.insertBefore(instruction, gate("exit"));
                    }
                }
                LabelNode start = new LabelNode();
                InsnList entry = gate("enter");
                entry.add(start);
                method.instructions.insert(entry);
                LabelNode end = new LabelNode();
                method.instructions.add(end);
                method.instructions.add(gate("exit"));
                method.instructions.add(new InsnNode(Opcodes.ATHROW));
                // Preserve the original handlers' priority, including Spring's own finally blocks.
                method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, end, null));
            }
            if (!found) throw new IllegalStateException("Unsupported FrameworkServlet.processRequest signature");
            type.accept(writer);
            byte[] result = writer.toByteArray();
            RequestGate.global().installed();
            StatusReporter.info("Request boundary installed for synchronous Spring MVC dispatches");
            return result;
        } catch (Throwable failure) {
            RequestGate.global().unavailable("Spring MVC request hook failed: " + failure.getClass().getSimpleName());
            StatusReporter.error("Request boundary could not be installed; class reloads will remain deferred.");
            return null;
        }
    }
}
