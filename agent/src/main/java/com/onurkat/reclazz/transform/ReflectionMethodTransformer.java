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
    private static final String RECORD="com/onurkat/reclazz/bootstrap/RecordReflection";
    private static final String HEADER="com/onurkat/reclazz/bootstrap/RecordHeader";
    private static final String BRIDGE="com/onurkat/reclazz/bootstrap/ReflectionBridge";
    private static Field reflectionData;
    private volatile int installedTargets;
    private volatile Throwable transformFailure;
    private enum Phase { ALL, CORE, RECORD }
    private final Phase phase;
    private static volatile boolean recordHooks;
    public ReflectionMethodTransformer(){this(Phase.ALL);}
    private ReflectionMethodTransformer(Phase phase){this.phase=phase;}
    public static boolean recordHooksAvailable(){return recordHooks;}
    public static void install(Instrumentation inst,
            java.util.function.BiFunction<Class<?>,String,java.lang.invoke.MethodHandle> initializerProvider) {
        recordHooks=false;
        Module bridge=ReflectionBridge.class.getModule(),agent=ReflectionMethodTransformer.class.getModule();
        try {
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
            installPhase(inst,new ReflectionMethodTransformer(Phase.CORE),15,Class.class,java.lang.reflect.Executable.class,java.lang.invoke.MethodHandles.Lookup.class,Field.class);
        } catch(Throwable failure) {
            MethodForge.disable();StatusReporter.warn("Added-member root reflection unavailable: "+failure);return;
        }
        try {
            var candidates=com.onurkat.reclazz.bootstrap.RecordConstructorReferences.class.getDeclaredField("candidates");candidates.setAccessible(true);
            candidates.set(null,(java.util.function.Predicate<Class<?>>)owner->RecordCohortCandidates.retain(owner.getClassLoader(),owner.getName().replace('.','/')));
            inst.redefineModule(Object.class.getModule(),Set.of(bridge),Map.of(),
                    Map.of("java.lang",Set.of(bridge),"sun.reflect.generics.factory",Set.of(bridge),"sun.reflect.generics.scope",Set.of(bridge),
                            "sun.reflect.generics.reflectiveObjects",Set.of(bridge),"sun.reflect.generics.repository",Set.of(bridge)),Set.of(),Map.of());
            if(!com.onurkat.reclazz.bootstrap.RecordReflection.initialize())throw new IllegalStateException("Record component reflection unavailable",com.onurkat.reclazz.bootstrap.RecordReflection.initializationFailure);
            if(!com.onurkat.reclazz.bootstrap.RecordHeader.initialize())throw new IllegalStateException("Record class metadata unavailable",com.onurkat.reclazz.bootstrap.RecordHeader.initializationFailure);
            com.onurkat.reclazz.bootstrap.RecordHeader.initializeComponents();
            installPhase(inst,new ReflectionMethodTransformer(Phase.RECORD),249,Class.class,Field.class,java.lang.reflect.RecordComponent.class,
                    java.lang.reflect.Constructor.class,java.lang.reflect.Method.class,Class.forName("sun.reflect.generics.reflectiveObjects.TypeVariableImpl"));
            recordHooks=true;
        }catch(Throwable failure){StatusReporter.warn("Stock record reflection unavailable; existing added-member reflection remains active: "+failure);}
    }
    private static void installPhase(Instrumentation inst,ReflectionMethodTransformer transformer,int expected,Class<?>... targets)throws Throwable {
        inst.addTransformer(transformer,true);
        try{
            inst.retransformClasses(targets);
            if(transformer.transformFailure!=null||transformer.installedTargets!=expected)
                throw new IllegalStateException("Incomplete "+transformer.phase+" reflection guards",transformer.transformFailure);
        }catch(Throwable failure){inst.removeTransformer(transformer);throw failure;}
    }
    private static boolean coreHook(int kind,String method){
        return kind==2||kind==4||kind==8&&(method.equals("getAnnotatedType")||method.equals("getModifiers")||method.startsWith("get")&&method.endsWith("FieldAccessor"))||kind==1&&Set.of("privateGetDeclaredMethods","privateGetDeclaredFields","privateGetDeclaredConstructors","privateGetPublicMethods","privateGetPublicFields").contains(method);
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

    private static MethodVisitor guardedHeader(MethodVisitor delegate,String guard,String guardDescriptor,String helper,String helperDescriptor,Type result) {
        return new MethodVisitor(Opcodes.ASM9,delegate){@Override public void visitCode(){
            super.visitCode();Label ordinary=new Label();super.visitVarInsn(Opcodes.ALOAD,0);
            super.visitMethodInsn(Opcodes.INVOKESTATIC,HEADER,guard,guardDescriptor,false);
            super.visitJumpInsn(Opcodes.IFEQ,ordinary);super.visitVarInsn(Opcodes.ALOAD,0);
            super.visitMethodInsn(Opcodes.INVOKESTATIC,HEADER,helper,helperDescriptor,false);
            if(helperDescriptor.endsWith("Ljava/lang/Object;"))super.visitTypeInsn(Opcodes.CHECKCAST,result.getInternalName());
            super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);
        }};
    }

    @Override public byte[] transform(ClassLoader loader,String name,Class<?> redefining,ProtectionDomain domain,byte[] bytes) {
        int kind=switch(name==null?"":name){case "java/lang/Class"->1;case "java/lang/reflect/Executable"->2;case "java/lang/invoke/MethodHandles$Lookup"->4;case "java/lang/reflect/Field"->8;case "java/lang/reflect/RecordComponent"->16;case "java/lang/reflect/Constructor"->32;case "java/lang/reflect/Method"->64;case "sun/reflect/generics/reflectiveObjects/TypeVariableImpl"->128;default->0;};
        if(kind==0||phase==Phase.CORE&&(kind&15)==0||phase==Phase.RECORD&&(kind==2||kind==4))return null;
        try {
            var reader=new ClassReader(bytes);var writer=new ClassWriter(reader,ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
            Set<String> hooks=new HashSet<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
                @Override public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] errors) {
                    MethodVisitor mv=super.visitMethod(access,method,desc,sig,errors);
                    if(phase==Phase.CORE&&!coreHook(kind,method)||phase==Phase.RECORD&&coreHook(kind,method))return mv;
                    if(kind==1 && Set.of("getGenericInfo","annotationData","getAnnotatedSuperclass","getAnnotatedInterfaces").contains(method)) {
                        String helper=switch(method){case "getGenericInfo"->"genericInfo";case "getAnnotatedSuperclass"->"annotatedSuperclass";case "getAnnotatedInterfaces"->"annotatedInterfaces";default->method;};
                        hooks.add(method);
                        return guardedHeader(mv,"hasView","(Ljava/lang/Class;)Z",helper,"(Ljava/lang/Class;)"+(method.equals("getGenericInfo")||method.equals("annotationData")?"Ljava/lang/Object;":Type.getReturnType(desc).getDescriptor()),Type.getReturnType(desc));
                    }
                    if(kind==8&&phase==Phase.CORE&&method.endsWith("FieldAccessor")&&method.startsWith("get")
                            &&Type.getReturnType(desc).getInternalName().equals("jdk/internal/reflect/FieldAccessor")) {
                        // A prepared static's write policy follows its current declaration (R31b audit round 2 F1a).
                        return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/onurkat/reclazz/bootstrap/PreparedStaticFinals","refreshAccessors","(Ljava/lang/reflect/Field;)V",false);
                        }};
                    }
                    if(kind==8&&method.equals("getModifiers")&&desc.equals("()I")) {
                        // A static final field that resetStatics made physically writable still reports final.
                        hooks.add(method);
                        return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitFieldInsn(Opcodes.GETFIELD,"java/lang/reflect/Field","modifiers","I");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/onurkat/reclazz/bootstrap/PreparedStaticFinals","modifiers","(Ljava/lang/reflect/Field;I)I",false);
                            super.visitInsn(Opcodes.IRETURN);
                        }};
                    }
                    if((kind==8||kind==32||kind==64)&&method.equals("getGenericInfo")) {
                        hooks.add(method);
                        return guardedHeader(mv,"hasMemberView","(Ljava/lang/Object;)Z","memberGenericInfo","(Ljava/lang/Object;)Ljava/lang/Object;",Type.getReturnType(desc));
                    }
                    if(kind==128 && Set.of("getAnnotations","getAnnotation","getAnnotatedBounds").contains(method)) {
                        hooks.add(method);String helper=switch(method){case "getAnnotations"->"variableAnnotations";case "getAnnotation"->"variableAnnotation";default->"variableAnnotatedBounds";};
                        return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();Label ordinary=new Label();super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HEADER,"hasVariableMetadata","(Ljava/lang/reflect/TypeVariable;)Z",false);
                            super.visitJumpInsn(Opcodes.IFEQ,ordinary);super.visitVarInsn(Opcodes.ALOAD,0);
                            if(method.equals("getAnnotation"))super.visitVarInsn(Opcodes.ALOAD,1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HEADER,helper,"(Ljava/lang/reflect/TypeVariable;"+(method.equals("getAnnotation")?"Ljava/lang/Class;":"")+")"+Type.getReturnType(desc).getDescriptor(),false);
                            super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);
                        }};
                    }
                    if(kind==128&&method.equals("getBounds")) {
                        hooks.add(method);return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HEADER,"physicalVariableBounds","(Ljava/lang/reflect/TypeVariable;)[Ljava/lang/reflect/Type;",false);
                            Label ordinary=new Label();super.visitInsn(Opcodes.DUP);super.visitJumpInsn(Opcodes.IFNULL,ordinary);super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);super.visitInsn(Opcodes.POP);
                        }};
                    }
                    if(kind==32&&method.equals("newInstanceWithCaller")&&desc.equals("([Ljava/lang/Object;ZLjava/lang/Class;)Ljava/lang/Object;")) {
                        return new MethodVisitor(Opcodes.ASM9,mv) {
                            @Override public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean face) {
                                if(opcode==Opcodes.INVOKEINTERFACE&&owner.equals("jdk/internal/reflect/ConstructorAccessor")
                                        &&called.equals("newInstance")&&descriptor.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
                                    hooks.add(method);
                                    // Keep the actual JDK access check and enum guard before this point.
                                    // The inactive path retains the original accessor and arguments.
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,MEMBER,"newRecordInstance","(Ljava/lang/reflect/Constructor;[Ljava/lang/Object;)Ljava/lang/Object;",false);
                                    var ordinary=new Label();super.visitInsn(Opcodes.DUP);super.visitJumpInsn(Opcodes.IFNULL,ordinary);
                                    super.visitInsn(Opcodes.DUP_X2);super.visitInsn(Opcodes.POP);super.visitInsn(Opcodes.POP2);super.visitInsn(Opcodes.ARETURN);
                                    super.visitLabel(ordinary);super.visitInsn(Opcodes.POP);
                                }
                                super.visitMethodInsn(opcode,owner,called,descriptor,face);
                            }
                        };
                    }
                    if(kind==16 && (method.equals("getGenericType") || method.equals("getAnnotatedType"))) {
                        hooks.add(method);String result=Type.getReturnType(desc).getDescriptor();
                        return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();Label ordinary=new Label();super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,RECORD,"isVirtual","(Ljava/lang/reflect/RecordComponent;)Z",false);
                            super.visitJumpInsn(Opcodes.IFEQ,ordinary);super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,RECORD,method.equals("getGenericType")?"genericType":"annotatedType",
                                    "(Ljava/lang/reflect/RecordComponent;)"+result,false);
                            super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);
                            if(method.equals("getGenericType")) {
                                Label physical=new Label();super.visitVarInsn(Opcodes.ALOAD,0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,RECORD,"hasPhysicalGenericView","(Ljava/lang/reflect/RecordComponent;)Z",false);
                                super.visitJumpInsn(Opcodes.IFEQ,physical);super.visitVarInsn(Opcodes.ALOAD,0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,RECORD,"physicalGenericType","(Ljava/lang/reflect/RecordComponent;)Ljava/lang/reflect/Type;",false);
                                super.visitInsn(Opcodes.ARETURN);super.visitLabel(physical);
                            }
                        }};
                    }
                    if(kind==1) {
                        if(method.equals("getRecordComponents") && desc.equals("()[Ljava/lang/reflect/RecordComponent;")) {
                            hooks.add(method);
                            return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitInsn(int opcode){
                                if(opcode==Opcodes.ARETURN){super.visitVarInsn(Opcodes.ALOAD,0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,BRIDGE,"mergeRecordComponents",
                                            "([Ljava/lang/reflect/RecordComponent;Ljava/lang/Class;)[Ljava/lang/reflect/RecordComponent;",false);}
                                super.visitInsn(opcode);
                            }};
                        }
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
                    if(kind==4 && method.equals("findStatic") && desc.equals("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;")) {
                        hooks.add(method);
                        return new MethodVisitor(Opcodes.ASM9,mv){@Override public void visitCode(){
                            super.visitCode();
                            for (int i=0;i<4;i++) super.visitVarInsn(Opcodes.ALOAD,i);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/onurkat/reclazz/bootstrap/InterfaceCalls","logicalStatic",
                                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",false);
                            Label ordinary=new Label();super.visitInsn(Opcodes.DUP);super.visitJumpInsn(Opcodes.IFNULL,ordinary);
                            super.visitInsn(Opcodes.ARETURN);super.visitLabel(ordinary);super.visitInsn(Opcodes.POP);
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
                case 1->Set.of("privateGetDeclaredMethods","privateGetDeclaredFields","privateGetDeclaredConstructors","privateGetPublicMethods","privateGetPublicFields","getRecordComponents","getGenericInfo","annotationData","getAnnotatedSuperclass","getAnnotatedInterfaces");
                case 2->Set.of("parameters","typeAnnotations","sharedGetParameterAnnotations","getAnnotatedReturnType0","getAnnotatedParameterTypes","getAnnotatedExceptionTypes","getAnnotatedReceiverType");
                case 4->Set.of("findStatic","findConstructor","unreflect","unreflectSpecial","unreflectConstructor","unreflectGetter","unreflectSetter","unreflectVarHandle");
                case 16->Set.of("getGenericType","getAnnotatedType");
                case 32->Set.of("newInstanceWithCaller","getGenericInfo");
                case 64->Set.of("getGenericInfo");
                case 128->Set.of("getAnnotations","getAnnotation","getAnnotatedBounds","getBounds");
                default->Set.of("getAnnotatedType","getGenericInfo","getModifiers");};
            if(phase!=Phase.ALL)expected=expected.stream().filter(method->phase==Phase.CORE?coreHook(kind,method):!coreHook(kind,method)).collect(java.util.stream.Collectors.toSet());
            if(!hooks.containsAll(expected))throw new IllegalStateException("Reflection hooks missing in "+name+": "+hooks);
            synchronized(this){installedTargets|=kind;}
            return writer.toByteArray();
        }catch(Throwable failure){transformFailure=failure;return null;}
    }
}
