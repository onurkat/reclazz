/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.*;

/** A post-refresh resolver reads immutable snapshots while native startup keeps the original map. */
public final class KafkaRetryResolverTransformer implements ClassFileTransformer {
    public static final String TARGET = "org/springframework/kafka/retrytopic/DefaultDestinationTopicResolver";

    private static final String KAFKA = "org/springframework/kafka/";
    private static final String PROCESSOR = KAFKA + "annotation/KafkaListenerAnnotationBeanPostProcessor";
    private static final String CONFIGURER = KAFKA + "retrytopic/RetryTopicConfigurer";
    private static final String REGISTRY = KAFKA + "config/KafkaListenerEndpointRegistry";
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/KafkaStartupRetry";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final Map<String,Set<String>> MEASURED = Map.of(
            TARGET, Set.of("ff4ea9adf1d8b5dc65cc1c810e39d92c13702ff7698f1649ea774b76ea750dd9",
                           "db3277c6f1c643bc6c27232aaed43e0bdb5b458e530569b5b51421ae650bdb5c"),
            PROCESSOR, Set.of("54b35a93513f62884781d4ffeb54b223d82718aa9350b30201937b94cfbd224e",
                              "9903f32390f6d7747b6565f89386e76982d734018a2a037380ff515cc86e759b"),
            CONFIGURER, Set.of("5285d9f16f07c749982e178f39f1118cf7dd0192f9b27268f390499495477fe7",
                               "fdb4f24cf39171b6c82fbeb04a7d0b3eced4c106448c3e71c9229127ef0dd55c"),
            REGISTRY, Set.of("26912f6d5b630d600fcd22d85227b0ef2b5eeed46af2d6e62f7c1600f0fab21b",
                             "ab2701f355ae611deed620e2d0c06e292de704749630df282c5c3adabbebf947"));
    public boolean targets(String internalName) { return internalName != null && MEASURED.containsKey(internalName); }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> previous, ProtectionDomain domain, byte[] bytes) {
        if (!targets(name)) return null;
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!MEASURED.get(name).contains(digest)) return null;
            if (Class.forName("com.onurkat.reclazz.bootstrap.KafkaRetryRoutes", false, loader)
                    != com.onurkat.reclazz.bootstrap.KafkaRetryRoutes.class
                    || name.equals(PROCESSOR) && Class.forName(NativeListenerSetupTransformer.BRIDGE.replace('/', '.'), false, loader)
                    != com.onurkat.reclazz.bootstrap.NativeListenerSetup.class
                    || Class.forName(BRIDGE.replace('/', '.'), false, loader) != com.onurkat.reclazz.bootstrap.KafkaStartupRetry.class)
                return null;
        } catch (LinkageError | ClassNotFoundException | java.security.NoSuchAlgorithmException unavailable) { return null; }
        var reader = new ClassReader(bytes);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] exceptions) {
                var mv = super.visitMethod(access, method, desc, sig, exceptions);
                if (name.equals(PROCESSOR) && method.equals("<init>"))
                    return NativeListenerSetupTransformer.observeConstruction(mv);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitMethodInsn(int op, String owner, String callee, String descriptor, boolean itf) {
                        if (name.equals(PROCESSOR) && method.equals("processKafkaListenerAnnotation")
                                && owner.equals(KAFKA + "config/MethodKafkaListenerEndpoint") && callee.equals("setBean")
                                && descriptor.equals("(Ljava/lang/Object;)V"))
                            NativeListenerSetupTransformer.rememberEndpoint(mv);
                        if (name.equals(PROCESSOR) && owner.equals(CONFIGURER) && callee.equals("processMainAndRetryListeners")) {
                            super.visitLdcInsn(new Handle(Opcodes.H_INVOKEVIRTUAL, owner, callee, descriptor, false));
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "configure", "(" + OBJECT.repeat(6)
                                    + "Ljava/lang/String;Ljava/lang/invoke/MethodHandle;)V", false);
                            return;
                        }
                        if (name.equals(TARGET) && method.equals("addDestinationTopics") && owner.equals("java/util/Map")
                                && callee.equals("computeIfAbsent")) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/onurkat/reclazz/bootstrap/KafkaRetryRoutes",
                                    "destinationFor", "(Ljava/util/Map;Ljava/lang/Object;Ljava/util/function/Function;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            return;
                        }
                        if (name.equals(TARGET) && method.equals("addDestinationTopics") && owner.equals("java/util/Map")
                                && callee.equals("putAll") && descriptor.equals("(Ljava/util/Map;)V")) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            if (desc.startsWith("(Ljava/lang/String;")) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD, TARGET, "sourceDestinationsHolderMap", "Ljava/util/Map;");
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/onurkat/reclazz/bootstrap/KafkaRetryRoutes",
                                        "nativeWriteForId", "(Ljava/util/Map;Ljava/util/Map;Ljava/lang/Object;Ljava/lang/String;Ljava/util/Map;)V", false);
                            } else {
                                super.visitInsn(Opcodes.ACONST_NULL);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/onurkat/reclazz/bootstrap/KafkaRetryRoutes",
                                        "nativeWrite", "(Ljava/util/Map;Ljava/util/Map;Ljava/lang/Object;Ljava/lang/String;)V", false);
                            }
                            return;
                        }
                        boolean product = name.equals(CONFIGURER) && callee.equals("registerSingleton")
                                && owner.equals("org/springframework/beans/factory/support/DefaultListableBeanFactory");
                        boolean endpoint = name.equals(CONFIGURER) && callee.equals("registerEndpoint")
                                && owner.equals(KAFKA + "config/KafkaListenerEndpointRegistrar");
                        if (product || endpoint) super.visitInsn(Opcodes.DUP2_X1);
                        super.visitMethodInsn(op, owner, callee, descriptor, itf);
                        if (product) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "product", "(Ljava/lang/String;" + OBJECT + ")V", false);
                        } else if (endpoint) {
                            super.visitInsn(Opcodes.POP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "endpoint", "(" + OBJECT + ")V", false);
                        } else if (name.equals(REGISTRY) && method.equals("registerListenerContainer")
                                && owner.equals(REGISTRY) && callee.equals("createListenerContainer")) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "constructed", "(" + OBJECT + OBJECT + ")V", false);
                        }
                    }
                    @Override public void visitFieldInsn(int op, String owner, String field, String descriptor) {
                        if (!method.equals("addDestinationTopics") && !method.equals("getDestinationTopicSynchronized") && op == Opcodes.GETFIELD && owner.equals(TARGET) && field.equals("sourceDestinationsHolderMap")
                                && descriptor.equals("Ljava/util/Map;")) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitFieldInsn(op, owner, field, descriptor);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/onurkat/reclazz/bootstrap/KafkaRetryRoutes",
                                    "view", "(Ljava/lang/Object;Ljava/util/Map;)Ljava/util/Map;", false);
                        } else super.visitFieldInsn(op, owner, field, descriptor);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
