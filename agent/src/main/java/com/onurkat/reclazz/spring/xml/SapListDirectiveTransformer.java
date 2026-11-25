/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Capture the actual processor order and list, on first definition of the two native processors. */
public final class SapListDirectiveTransformer implements ClassFileTransformer {
    private static final String MERGE = "de/hybris/platform/spring/config/ListMergeDirectiveBeanPostProcessor";
    private static final String MODIFY = "de/hybris/platform/converters/impl/ModifyPopulatorListBeanPostProcessor";
    private static final String OBSERVER = "com/onurkat/reclazz/spring/xml/SapDirectiveHistory";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if (redefining != null || (!MERGE.equals(name) && !MODIFY.equals(name))) return null;
        try {
            if (Class.forName(OBSERVER.replace('/', '.'), false, loader) != SapDirectiveHistory.class) return null;
            ClassReader reader = new ClassReader(bytes);
            ClassNode type = new ClassNode();
            reader.accept(type, 0);
            boolean observed = false, named = false;
            for (MethodNode method : type.methods) {
                if (MERGE.equals(name) && method.name.equals("processMergeDirective")
                        && method.desc.equals("(Lde/hybris/platform/spring/config/ListMergeDirective;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)V")) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(MERGE) || !call.name.equals("resolveListBean")
                                || !call.desc.equals(method.desc.replace(")V", ")Ljava/util/List;"))) continue;
                        // Keep the resolved native list on the stack. The SDK immediately stores it for its own processors.
                        InsnList start = new InsnList();
                        start.add(new InsnNode(Opcodes.DUP));
                        for (int slot : new int[]{0, 1, 2, 3, 4}) start.add(new VarInsnNode(Opcodes.ALOAD, slot));
                        start.add(observer("beforeMerge", "(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"));
                        observeReturns(method, call, start);
                        observed = true;
                        break;
                    }
                }
                if (MODIFY.equals(name) && method.name.equals("postProcessAfterInitialization")
                        && method.desc.equals("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;")) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (insn instanceof MethodInsnNode call && call.owner.equals(MODIFY) && call.name.equals("process")
                                && call.desc.equals("(Lde/hybris/platform/converters/impl/ModifyPopulatorList;)V")) {
                            InsnList remember = new InsnList();
                            remember.add(new InsnNode(Opcodes.DUP));
                            remember.add(new VarInsnNode(Opcodes.ALOAD, 2));
                            remember.add(observer("nameModify", "(Ljava/lang/Object;Ljava/lang/String;)V"));
                            method.instructions.insertBefore(call, remember);
                            named = true;
                        }
                    }
                }
                if (MODIFY.equals(name) && method.name.equals("processOnTarget")
                        && method.desc.equals("(Lde/hybris/platform/converters/impl/ModifyPopulatorList;Lde/hybris/platform/converters/PopulatorList;)V")) {
                    InsnList start = new InsnList();
                    for (int slot : new int[]{0, 1, 2}) start.add(new VarInsnNode(Opcodes.ALOAD, slot));
                    start.add(observer("beforeModify", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                    observeReturns(method, null, start);
                    observed = true;
                }
            }
            if (!observed || (MODIFY.equals(name) && !named)) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            type.accept(writer);
            return writer.toByteArray();
        } catch (Exception | LinkageError unsupported) { return null; }
    }

    private static MethodInsnNode observer(String method, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, OBSERVER, method, descriptor, false);
    }

    private static void observeReturns(MethodNode method, AbstractInsnNode after, InsnList start) {
        int token = method.maxLocals++;
        start.add(new VarInsnNode(Opcodes.ASTORE, token));
        if (after == null) method.instructions.insert(start);
        else {
            InsnList init = new InsnList();
            init.add(new InsnNode(Opcodes.ACONST_NULL));
            init.add(new VarInsnNode(Opcodes.ASTORE, token));
            method.instructions.insert(init);
            method.instructions.insert(after, start);
        }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList finish = new InsnList();
            finish.add(new VarInsnNode(Opcodes.ALOAD, token));
            finish.add(observer("after", "(Ljava/lang/Object;)V"));
            method.instructions.insertBefore(insn, finish);
        }
    }
}
