/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.platform.SapProcessResources;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Observe membership changes in Spring's existing singleton registry without instantiating beans. */
public final class SingletonRegistryRevisionTransformer implements ClassFileTransformer {
    private static final String REGISTRY = "org/springframework/beans/factory/support/DefaultSingletonBeanRegistry";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!REGISTRY.equals(name) || redefining != null) return null;
        try {
            if (Class.forName(SapProcessResources.class.getName(), false, loader) != SapProcessResources.class) return null;
            ClassReader reader = new ClassReader(bytes);
            ClassNode tree = new ClassNode();
            reader.accept(tree, 0);
            if (!observe(tree, "addSingleton", "(Ljava/lang/String;Ljava/lang/Object;)V", "add")
                    || !observe(tree, "removeSingleton", "(Ljava/lang/String;)V", "remove")
                    || !observe(tree, "clearSingletonCache", "()V", "clear")) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            tree.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException | LinkageError | ClassNotFoundException unsupported) {
            return null;
        }
    }

    private static boolean observe(ClassNode tree, String method, String descriptor, String operation) {
        for (MethodNode target : tree.methods) {
            if (!target.name.equals(method) || !target.desc.equals(descriptor)) continue;
            for (AbstractInsnNode instruction : target.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("java/util/Set")
                        && call.name.equals(operation)) {
                    InsnList notify = new InsnList();
                    notify.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    notify.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "com/onurkat/reclazz/platform/SapProcessResources", "registryChanged", "(Ljava/lang/Object;)V", false));
                    target.instructions.insert(call, notify);
                    return true;
                }
            }
        }
        return false;
    }
}
