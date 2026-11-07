/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Observe only the native lazy cache load and its resource parse calls. No retransformation. */
public final class SapProcessDefinitionTransformer implements ClassFileTransformer {
    private static final String PREFIX = "de/hybris/platform/processengine/definition/";
    private static final String OBSERVER = "com/onurkat/reclazz/platform/SapProcessDefinitions";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (redefining != null || name == null || !name.startsWith(PREFIX)) return null;
        String method, descriptor, kind;
        if (name.equals(PREFIX + "ProcessDefinitionsCache")) {
            method = "loadInitialDefinitions"; descriptor = "()V"; kind = "Cache";
        } else if (name.equals(PREFIX + "ProcessDefinitionsProvider")) {
            method = "getInitialActiveDefinitions"; descriptor = "()Ljava/lang/Iterable;"; kind = "Provider";
        } else if (name.equals(PREFIX + "XMLProcessDefinitionsReader")) {
            method = "from";
            descriptor = "(Lorg/springframework/core/io/Resource;)L" + PREFIX + "ProcessDefinition;";
            kind = "Reader";
        } else return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode type = new ClassNode(Opcodes.ASM9);
            reader.accept(type, ClassReader.EXPAND_FRAMES);
            boolean found = false;
            for (MethodNode m : type.methods) if (m.name.equals(method) && m.desc.equals(descriptor)) {
                found = true;
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (insn.getOpcode() != Opcodes.RETURN && insn.getOpcode() != Opcodes.ARETURN) continue;
                    InsnList exit = new InsnList();
                    if (kind.equals("Reader")) {
                        exit.add(new InsnNode(Opcodes.DUP));
                        exit.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        exit.add(new InsnNode(Opcodes.SWAP));
                        exit.add(call("parsed", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    } else {
                        exit.add(new InsnNode(Opcodes.ICONST_1));
                        exit.add(call("exit" + kind, "(Z)V"));
                    }
                    m.instructions.insertBefore(insn, exit);
                }
                if (!kind.equals("Reader")) {
                    LabelNode start = new LabelNode(), end = new LabelNode();
                    InsnList enter = new InsnList();
                    enter.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    enter.add(call("enter" + kind, "(Ljava/lang/Object;)V"));
                    enter.add(start); m.instructions.insert(enter);
                    m.instructions.add(end); m.instructions.add(new InsnNode(Opcodes.ICONST_0));
                    m.instructions.add(call("exit" + kind, "(Z)V"));
                    m.instructions.add(new InsnNode(Opcodes.ATHROW));
                    m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, end, null));
                }
            }
            if (!found) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            type.accept(writer);
            return writer.toByteArray();
        } catch (Throwable unavailable) { return null; }
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, OBSERVER, name, descriptor, false);
    }
}
