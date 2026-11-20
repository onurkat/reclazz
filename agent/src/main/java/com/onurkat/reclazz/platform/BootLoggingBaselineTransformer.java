/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/** Records the level before Boot's explicit property application, after debug/trace defaults. */
public final class BootLoggingBaselineTransformer implements ClassFileTransformer {
    private static final String BRIDGE="com/onurkat/reclazz/bootstrap/BootLoggingBaselineRecorder";
    private static final String LISTENER="org/springframework/boot/context/logging/LoggingApplicationListener";
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                                      ProtectionDomain domain, byte[] bytes) {
        boolean listener=LISTENER.equals(name);
        boolean system="org/springframework/boot/logging/logback/LogbackLoggingSystem".equals(name)
                || "org/springframework/boot/logging/log4j2/Log4J2LoggingSystem".equals(name);
        if ((!listener && !system) || redefined!=null) return null;
        try {
            ClassReader reader=new ClassReader(bytes);
            ClassWriter writer=new com.onurkat.reclazz.transform.SafeClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions) {
                    MethodVisitor target=super.visitMethod(access,method,descriptor,signature,exceptions);
                    if(system && method.equals("setLogLevel") && descriptor.equals("(Ljava/lang/String;Lorg/springframework/boot/logging/LogLevel;)V"))
                        return new AdviceAdapter(Opcodes.ASM9,target,access,method,descriptor) {
                            @Override protected void onMethodEnter(){loadThis();loadArg(0);visitMethodInsn(INVOKESTATIC,BRIDGE,"beforeSet","(Ljava/lang/Object;Ljava/lang/String;)V",false);}
                        };
                    boolean startup=listener && method.equals("initializeFinalLoggingLevels")
                            && descriptor.equals("(Lorg/springframework/core/env/ConfigurableEnvironment;Lorg/springframework/boot/logging/LoggingSystem;)V");
                    boolean explicit=listener && method.equals("setLogLevels")
                            && descriptor.equals("(Lorg/springframework/boot/logging/LoggingSystem;Lorg/springframework/core/env/ConfigurableEnvironment;)V");
                    if(!startup && !explicit)return target;
                    return new AdviceAdapter(Opcodes.ASM9,target,access,method,descriptor) {
                        final Label begin=new Label(),end=new Label(),handler=new Label();
                        void finish(){visitMethodInsn(INVOKESTATIC,BRIDGE,startup?"endStartup":"endExplicit","()V",false);}
                        @Override protected void onMethodEnter(){visitMethodInsn(INVOKESTATIC,BRIDGE,startup?"beginStartup":"beginExplicit","()V",false);visitLabel(begin);}
                        @Override protected void onMethodExit(int opcode){if(opcode!=ATHROW)finish();}
                        @Override public void visitMaxs(int maxStack,int maxLocals) {
                            visitLabel(end);visitTryCatchBlock(begin,end,handler,null);visitLabel(handler);finish();
                            // Bypass onMethodExit: this is the finally handler's rethrow.
                            mv.visitInsn(ATHROW);super.visitMaxs(maxStack,maxLocals);
                        }
                    };
                }
            },ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch(RuntimeException failure) {
            com.onurkat.reclazz.ui.StatusReporter.warn("Boot logging baseline capture unavailable for "+name+": "+failure);
            return null;
        }
    }
}
