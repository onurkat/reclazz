/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.*;

/** Observes the native annotation and factory paths before the registry starts its consumer. */
public final class NativeListenerSetupTransformer implements ClassFileTransformer {
    static final String BRIDGE = "com/onurkat/reclazz/bootstrap/NativeListenerSetup";
    private static final String OBJECTS = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final Map<String,Set<String>> MEASURED = Map.of(
            "org/springframework/kafka/config/AbstractKafkaListenerContainerFactory",
            Set.of("c199cc56eb76d88a57817352fdd4f68859d698fb65f9d6c4357b55fe98f56041", "2700346ffcc958c2d69677dbfee8f0d80a7561311b25b71d5f7a77d373458352"),
            "org/springframework/amqp/rabbit/annotation/RabbitListenerAnnotationBeanPostProcessor",
            Set.of("e92833d7b8d6fb875669c2971ca41913c8e2cf277d99719d6644059908bf046d", "a00fb25e329ec55e2f53aba6ef29d65bebd7e0488ed1079173ee98426907dab6"),
            "org/springframework/amqp/rabbit/config/AbstractRabbitListenerContainerFactory",
            Set.of("993e0713609983a22c0347b650fecb012533723bbc74c6607b65b66d33f60b29", "3bbc72d4ffbb83d0368d24cd18892e669504385bb19f0b74bb5f99b98dd51219"),
            "org/springframework/jms/annotation/JmsListenerAnnotationBeanPostProcessor",
            Set.of("1e95135d25ae0f202ae5e00e0c3d01cfd86a582b10c6c8ca3b229c7c577d9bf3", "6d6aa9d2358936d64335fead4d5810905626a45381d6e0b2d8e94f04e2513518"),
            "org/springframework/jms/config/AbstractJmsListenerContainerFactory",
            Set.of("185c630466d83d5f31c6221ee68bdfd520f5d6c994232f78595a1a482669055b", "797048989ddc86445d12bf6495006fbc519daa7b7812a7301b029b4691df3ca4"));
    public boolean targets(String name) { return name != null && MEASURED.containsKey(name); }

    static MethodVisitor observeConstruction(MethodVisitor target) {
        return new MethodVisitor(Opcodes.ASM9, target) {
            @Override public void visitInsn(int opcode) {
                if (opcode == Opcodes.RETURN) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "constructed", "(Ljava/lang/Object;)V", false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    static void rememberEndpoint(MethodVisitor mv) {
        // Preserve the endpoint and bean operands for Spring's setBean call.
        mv.visitInsn(Opcodes.DUP2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "annotated", OBJECTS, false);
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> previous, ProtectionDomain domain, byte[] bytes) {
        if (!targets(name)) return null;
        try {
            if (!MEASURED.get(name).contains(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    || Class.forName(BRIDGE.replace('/','.'),false,loader) != com.onurkat.reclazz.bootstrap.NativeListenerSetup.class)
                return null;
        } catch (ClassNotFoundException | LinkageError | java.security.NoSuchAlgorithmException unavailable) {
            return null;
        }
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
            @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions) {
                var target=super.visitMethod(access,method,descriptor,signature,exceptions);
                if (method.equals("<init>")) return observeConstruction(target);
                if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE)) != 0) return target;
                if (name.endsWith("Factory") && method.equals("createListenerContainer")
                        && Type.getArgumentTypes(descriptor).length == 1) return new MethodVisitor(Opcodes.ASM9,target) {
                    @Override public void visitInsn(int op) {
                        if (op==Opcodes.ARETURN) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitVarInsn(Opcodes.ALOAD,1);
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,BRIDGE,"created",OBJECTS,false);
                        }
                        super.visitInsn(op);
                    }
                };
                if (!method.equals("processJmsListener") && !method.equals("processListener")) return target;
                return new MethodVisitor(Opcodes.ASM9,target) {
                    @Override public void visitMethodInsn(int op,String owner,String callee,String desc,boolean itf) {
                        if (callee.equals("setBean") && desc.equals("(Ljava/lang/Object;)V")
                                && (owner.endsWith("/MethodJmsListenerEndpoint") || owner.endsWith("/MethodRabbitListenerEndpoint")))
                            rememberEndpoint(target);
                        super.visitMethodInsn(op,owner,callee,desc,itf);
                    }
                };
            }
        },0);
        return writer.toByteArray();
    }
}
