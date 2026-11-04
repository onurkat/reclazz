/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.MethodForge;
import com.onurkat.reclazz.bootstrap.ReflectionBridge;
import com.onurkat.reclazz.ui.StatusReporter;
import org.objectweb.asm.*;
import java.lang.instrument.*;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.*;

/** Installs logical roots and guards every supported JDK entry before it can resolve a virtual VM slot. */
public final class ReflectionMethodTransformer implements ClassFileTransformer {
    private static final String FORGE="com/onurkat/reclazz/bootstrap/MethodForge";
    private static final String MEMBER="com/onurkat/reclazz/bootstrap/MemberForge";
    private static final String BRIDGE="com/onurkat/reclazz/bootstrap/ReflectionBridge";
    private static Field reflectionData;
    private volatile int installedTargets;
    private volatile Throwable transformFailure;
    public static void install(Instrumentation inst,
            java.util.function.BiFunction<Class<?>,String,java.lang.invoke.MethodHandle> initializerProvider) {
        try {
            Module bridge=ReflectionBridge.class.getModule();
            Module agent=ReflectionMethodTransformer.class.getModule();
            inst.redefineModule(Object.class.getModule(),Set.of(bridge),Map.of(),
                    Map.of("java.lang.reflect",Set.of(bridge),"java.lang.invoke",Set.of(bridge),
                            "sun.invoke.util",Set.of(bridge),"jdk.internal.reflect",Set.of(bridge),"java.lang",Set.of(agent)),Set.of(),Map.of());
            if(!MethodForge.initialize())throw new IllegalStateException("Reflection constructors/accessors unavailable: "+MethodForge.initializationFailure,MethodForge.initializationFailure);
            com.onurkat.reclazz.bootstrap.MemberForge.installIntVarHandle();
            com.onurkat.reclazz.bootstrap.MemberForge.initializerProvider(initializerProvider);
            reflectionData=Class.class.getDeclaredField("reflectionData");reflectionData.setAccessible(true);
            ReflectionBridge.mergeDeclaredRoots(new java.lang.reflect.Method[0],Object.class,false);
            ReflectionBridge.mergeFieldRoots(new Field[0],Object.class,false);
            ReflectionBridge.mergeConstructorRoots(new java.lang.reflect.Constructor<?>[0],Object.class,false);
            var transformer=new ReflectionMethodTransformer();
            inst.addTransformer(transformer,true);
            try {
                inst.retransformClasses(Class.class,java.lang.reflect.Executable.class,java.lang.invoke.MethodHandles.Lookup.class,Field.class);
                if(transformer.transformFailure!=null || transformer.installedTargets!=15)
                    throw new IllegalStateException("Incomplete reflection guards",transformer.transformFailure);
            } catch(Throwable failure){inst.removeTransformer(transformer);throw failure;}
        } catch(Throwable failure) {
            MethodForge.disable();
            StatusReporter.warn("Added-member root reflection unavailable: "+failure);
        }
    }

    public static void invalidate(Instrumentation inst,Class<?> owner) {
        if(reflectionData==null)return;
        try {
            // The new members are already visible. Flush JavaBeans before the slow scan of every loaded class, so
            // a request in between does not see an added method next to bean properties computed before it, and
            // again after it, for bean info computed during the scan from a subclass's old reflection data.
            var flush=Class.forName("java.beans.Introspector").getMethod("flushCaches");
            flush.invoke(null);
            for(Class<?> type:inst.getAllLoadedClasses())if(owner.isAssignableFrom(type)) {
                ReflectionBridge.inheritPublicCacheMode(owner,type);reflectionData.set(type,null);
            }
            flush.invoke(null);
        } catch(Throwable failure) {StatusReporter.warn("Could not refresh reflection/JavaBeans caches: "+failure);}
    }

    @Override public byte[] transform(ClassLoader loader,String name,Class<?> redefining,ProtectionDomain domain,byte[] bytes) {
        int kind=switch(name==null?"":name){case "java/lang/Class"->1;case "java/lang/reflect/Executable"->2;case "java/lang/invoke/MethodHandles$Lookup"->4;case "java/lang/reflect/Field"->8;default->0;};
        if(kind==0)return null;
        try {
            var reader=new ClassReader(bytes);var writer=new ClassWriter(reader,ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
            Set<String> hooks=new HashSet<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
                @Override public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] errors) {
                    MethodVisitor mv=super.visitMethod(access,method,desc,sig,errors);
                    if(kind==1) {
                        String component=switch(method){case "privateGetDeclaredMethods"->"Method";case "privateGetDeclaredFields"->"Field";case "privateGetDeclaredConstructors"->"Constructor";default->null;};
                        if(component!=null && desc.equals("(Z)[Ljava/lang/reflect/"+component+";")) {
                            hooks.add(method);
                            String merge=component.equals("Method")?"mergeDeclaredRoots":component.equals("Field")?"mergeFieldRoots":"mergeConstructorRoots";
                            return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitInsn(int opcode){
                                if(opcode==Opcodes.ARETURN){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ILOAD,1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,BRIDGE,merge,"([Ljava/lang/reflect/"+component+";Ljava/lang/Class;Z)[Ljava/lang/reflect/"+component+";",false);}
                                super.visitInsn(opcode);
                            }};
                        }
                        if(method.equals("privateGetPublicMethods") || method.equals("privateGetPublicFields"))return new MethodVisitor(Opcodes.ASM9,mv){
                            @Override public void visitFieldInsn(int opcode,String owner,String field,String descriptor){
                                super.visitFieldInsn(opcode,owner,field,descriptor);
                                if(opcode==Opcodes.GETFIELD && owner.equals("java/lang/Class$ReflectionData") && (field.equals("publicMethods") || field.equals("publicFields"))) {
                                    super.visitVarInsn(Opcodes.ALOAD,0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,BRIDGE,field.equals("publicMethods")?"publicMethodCache":"publicFieldCache","("+descriptor+"Ljava/lang/Class;)"+descriptor,false);
                                    hooks.add(method);
                                }
                            }
                        };
                    }
                    if(kind==2) {
                        String helper=switch(method){case "sharedGetParameterAnnotations"->"parameterAnnotations";case "getAnnotatedReturnType0"->"annotatedReturn";case "getAnnotatedParameterTypes"->"annotatedParameters";case "getAnnotatedExceptionTypes"->"annotatedExceptions";case "getAnnotatedReceiverType"->"annotatedReceiver";default->null;};
                        MethodVisitor next=mv;
                        if(helper!=null) {
                            hooks.add(method);String result=Type.getReturnType(desc).getDescriptor();
                            next=new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                                super.visitCode();Label ordinary=new Label();super.visitVarInsn(Opcodes.ALOAD,0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,FORGE,"hasMetadata","(Ljava/lang/reflect/Executable;)Z",false);
                                super.visitJumpInsn(Opcodes.IFEQ,ordinary);super.visitVarInsn(Opcodes.ALOAD,0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,FORGE,helper,"(Ljava/lang/reflect/Executable;)"+result,false);
                                super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);
                            }};
                        }
                        return new MethodVisitor(Opcodes.ASM9,next){
                            @Override public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean itf){
                                if(owner.equals("java/lang/reflect/Executable") && called.equals("getParameters0") && descriptor.equals("()[Ljava/lang/reflect/Parameter;")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,FORGE,"parameters","(Ljava/lang/reflect/Executable;)[Ljava/lang/reflect/Parameter;",false);hooks.add("parameters");
                                } else if(owner.equals("java/lang/reflect/Executable") && called.equals("getTypeAnnotationBytes0") && descriptor.equals("()[B")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,FORGE,"typeAnnotations","(Ljava/lang/reflect/Executable;)[B",false);hooks.add("typeAnnotations");
                                } else super.visitMethodInsn(opcode,owner,called,descriptor,itf);
                            }
                        };
                    }
                    if(kind==8 && method.equals("getAnnotatedType") && desc.equals("()Ljava/lang/reflect/AnnotatedType;")) {
                        hooks.add(method);
                        return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();Label ordinary=new Label();super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,MEMBER,"isField","(Ljava/lang/reflect/Field;)Z",false);super.visitJumpInsn(Opcodes.IFEQ,ordinary);
                            super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,MEMBER,"annotatedField","(Ljava/lang/reflect/Field;)Ljava/lang/reflect/AnnotatedType;",false);
                            super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);
                        }};
                    }
                    if(kind==4 && method.equals("findConstructor") && desc.equals("(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;")) {
                        hooks.add(method);
                        return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitVarInsn(Opcodes.ALOAD,2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,MEMBER,"findConstructor","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",false);
                            Label ordinary=new Label();super.visitInsn(Opcodes.DUP);super.visitJumpInsn(Opcodes.IFNULL,ordinary);super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);super.visitInsn(Opcodes.POP);
                        }};
                    }
                    if(kind==4 && Set.of("unreflect","unreflectSpecial","unreflectConstructor","unreflectGetter","unreflectSetter","unreflectVarHandle").contains(method)) {
                        hooks.add(method);
                        return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();
                            if(method.equals("unreflectVarHandle")) {
                                super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,MEMBER,"unreflectVarHandle","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/reflect/Field;)Ljava/lang/invoke/VarHandle;",false);
                                Label ordinary=new Label();super.visitInsn(Opcodes.DUP);super.visitJumpInsn(Opcodes.IFNULL,ordinary);super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);super.visitInsn(Opcodes.POP);return;
                            }
                            super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);
                            switch(method) {
                                case "unreflect","unreflectSpecial"->{
                                    super.visitInsn(method.equals("unreflect")?Opcodes.ACONST_NULL:Opcodes.NOP);
                                    if(method.equals("unreflectSpecial"))super.visitVarInsn(Opcodes.ALOAD,2);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,FORGE,method,"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/reflect/Method;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",false);
                                }
                                case "unreflectConstructor"->super.visitMethodInsn(Opcodes.INVOKESTATIC,MEMBER,method,"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/reflect/Constructor;)Ljava/lang/invoke/MethodHandle;",false);
                                default->{super.visitInsn(method.equals("unreflectSetter")?Opcodes.ICONST_1:Opcodes.ICONST_0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,MEMBER,"unreflectField","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/reflect/Field;Z)Ljava/lang/invoke/MethodHandle;",false);}
                            }
                            Label ordinary=new Label();super.visitInsn(Opcodes.DUP);super.visitJumpInsn(Opcodes.IFNULL,ordinary);super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);super.visitInsn(Opcodes.POP);
                        }};
                    }
                    return mv;
                }
            },ClassReader.EXPAND_FRAMES);
            Set<String> expected=switch(kind){
                case 1->Set.of("privateGetDeclaredMethods","privateGetDeclaredFields","privateGetDeclaredConstructors","privateGetPublicMethods","privateGetPublicFields");
                case 2->Set.of("parameters","typeAnnotations","sharedGetParameterAnnotations","getAnnotatedReturnType0","getAnnotatedParameterTypes","getAnnotatedExceptionTypes","getAnnotatedReceiverType");
                case 4->Set.of("findConstructor","unreflect","unreflectSpecial","unreflectConstructor","unreflectGetter","unreflectSetter","unreflectVarHandle");
                default->Set.of("getAnnotatedType");};
            if(!hooks.containsAll(expected))throw new IllegalStateException("Reflection hooks missing in "+name+": "+hooks);
            synchronized(this){installedTargets|=kind;}
            return writer.toByteArray();
        }catch(Throwable failure){transformFailure=failure;return null;}
    }
}
