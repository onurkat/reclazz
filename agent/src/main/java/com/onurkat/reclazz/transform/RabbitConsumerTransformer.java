/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Track real Spring Rabbit workers from scheduling through the complete run method. */
public final class RabbitConsumerTransformer implements ClassFileTransformer {
    private static final String CONTAINER = "org/springframework/amqp/rabbit/listener/SimpleMessageListenerContainer";
    public static final String TARGET = CONTAINER + "$AsyncMessageProcessingConsumer";
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/RabbitConsumerBridge";
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name) || redefining != null) return null;
        try {
            var node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
            for (var method : node.methods) for (var insn : method.instructions)
                if (insn instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) return null;
            var ctor = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(
                    "(L" + CONTAINER + ";Lorg/springframework/amqp/rabbit/listener/BlockingQueueConsumer;)V")).findFirst().orElseThrow();
            var run = node.methods.stream().filter(m -> m.name.equals("run") && m.desc.equals("()V")).findFirst().orElseThrow();
            if (node.fields.stream().noneMatch(f -> f.name.equals("this$0") && f.desc.equals("L" + CONTAINER + ";")))
                throw new IllegalStateException("Rabbit worker owner field unavailable");
            for (var insn : ctor.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN)
                ctor.instructions.insertBefore(insn, call("created"));
            var start = new LabelNode(); run.instructions.insert(start);
            for (var insn : run.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN)
                run.instructions.insertBefore(insn, call("finished"));
            var end = new LabelNode(); var handler = new LabelNode();
            run.instructions.add(end); run.instructions.add(handler);
            // The Throwable remains below the bridge arguments on the stack.
            run.instructions.add(call("finished")); run.instructions.add(new InsnNode(Opcodes.ATHROW));
            run.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
            var clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
            if (clinit == null) {
                clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                clinit.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(clinit);
            }
            for (var insn : clinit.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) {
                var mark = new InsnList(); mark.add(new LdcInsnNode(Type.getObjectType(TARGET)));
                mark.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "markHooked", "(Ljava/lang/Class;)V", false));
                clinit.instructions.insertBefore(insn, mark);
            }
            var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected ClassLoader getClassLoader() { return loader == null ? super.getClassLoader() : loader; }
            };
            node.accept(writer); return writer.toByteArray();
        } catch (Throwable failure) {
            StatusReporter.warn("Rabbit worker hook unavailable: " + com.onurkat.reclazz.ui.Failures.describe(failure));
            return null;
        }
    }
    private static InsnList call(String method) {
        var code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "this$0", "L" + CONTAINER + ";"));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, method, "(Ljava/lang/Object;)V", false));
        return code;
    }
}
