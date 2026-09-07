/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.CacheDependencyLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Observe Spring Cache's synchronous interceptor in both Spring 5 and 6. */
public final class CacheComputationTransformer implements ClassFileTransformer {
    public static final String TARGET = "org/springframework/cache/interceptor/CacheAspectSupport";
    private static final String LEDGER = "com/onurkat/reclazz/bootstrap/CacheDependencyLedger";
    private static final String EXECUTE = "(Lorg/springframework/cache/interceptor/CacheOperationInvoker;Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String CACHES = "(Lorg/springframework/cache/interceptor/CacheOperationInvocationContext;Lorg/springframework/cache/interceptor/CacheResolver;)Ljava/util/Collection;";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        if (redefining != null) CacheDependencyLedger.partialCoverage();
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode type = new ClassNode(Opcodes.ASM9);
            reader.accept(type, ClassReader.EXPAND_FRAMES);
            boolean execute = false, caches = false;
            for (MethodNode m : type.methods) {
                if (m.name.equals("execute") && m.desc.equals(EXECUTE)) {
                    execute = true;
                    for (AbstractInsnNode insn : m.instructions.toArray()) {
                        if (insn.getOpcode() == Opcodes.ARETURN) m.instructions.insertBefore(insn, call("close", "()V"));
                    }
                    LabelNode start = new LabelNode(), end = new LabelNode();
                    InsnList entry = new InsnList(); entry.add(call("open", "()V")); entry.add(start);
                    m.instructions.insert(entry);
                    m.instructions.add(end); m.instructions.add(call("close", "()V"));
                    m.instructions.add(new InsnNode(Opcodes.ATHROW));
                    m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, end, null));
                } else if (m.name.equals("getCaches") && m.desc.equals(CACHES)) {
                    caches = true;
                    for (AbstractInsnNode insn : m.instructions.toArray()) if (insn.getOpcode() == Opcodes.ARETURN) {
                        InsnList observe = new InsnList(); observe.add(new InsnNode(Opcodes.DUP));
                        observe.add(call("cachesInUse", "(Ljava/util/Collection;)V"));
                        m.instructions.insertBefore(insn, observe);
                    }
                }
            }
            if (!execute || !caches) throw new IllegalStateException("Unsupported cache interceptor signatures");
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            type.accept(writer);
            byte[] result = writer.toByteArray();
            StatusReporter.info("Spring cache dependency observation installed" + (redefining != null ? " (partial attach history)" : ""));
            return result;
        } catch (Throwable failure) {
            CacheDependencyLedger.partialCoverage();
            StatusReporter.warn("Spring cache dependency hook unavailable; conservative annotation fallback retained");
            return null;
        }
    }
    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, LEDGER, name, desc, false);
    }
}
