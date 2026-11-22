/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import java.util.*;
import java.util.function.Predicate;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Keep constructor references symbolic until the bootstrap can select the logical factory. */
final class ConstructorReferenceRewriter implements Opcodes {
    private static final String RUNTIME="com/onurkat/reclazz/bootstrap/RecordConstructorReferences";
    private static final String META=RUNTIME+"$Metadata";
    private static final Handle BOOTSTRAP=new Handle(H_INVOKESTATIC,RUNTIME,"bootstrap","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",false);
    private ConstructorReferenceRewriter() { }
    static void rewrite(ClassNode source,Predicate<String> watched,Predicate<String> candidate,boolean companion) {
        var creators=new HashMap<String,MethodInsnNode>();
        for(var method:source.methods) {var constructor=creator(method);if(constructor!=null)creators.put(method.name+method.desc,constructor);}
        for(var method:source.methods)for(var instruction:method.instructions) {
            if(!(instruction instanceof InvokeDynamicInsnNode site)||!site.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    ||!Set.of("metafactory","altMetafactory").contains(site.bsm.getName())||site.bsmArgs.length<3||!(site.bsmArgs[1] instanceof Handle implementation))continue;
            MethodInsnNode creator=implementation.getOwner().equals(source.name)?creators.get(implementation.getName()+implementation.getDesc()):null;
            boolean direct=implementation.getTag()==H_NEWINVOKESPECIAL&&implementation.getName().equals("<init>");
            if(!direct&&creator==null)continue;
            Object[] original=site.bsmArgs;boolean alternative=site.bsm.getName().equals("altMetafactory");
            String owner=direct?implementation.getOwner():creator.owner,descriptor=direct?implementation.getDesc():creator.desc;
            if(!watched.test(owner))continue;
            boolean guarded=candidate.test(owner);
            if(!guarded&&!(companion&&direct))continue;
            var arguments=new ArrayList<Object>();
            arguments.add(original[0]);arguments.add(Type.getObjectType(owner));arguments.add(descriptor);arguments.add(original[2]);
            arguments.add(Type.getObjectType(source.name));arguments.add(template(source.name,site.name,site.desc,original,alternative));arguments.add((alternative?1:0)|(guarded?0:2));
            arguments.add(implementation.getTag());arguments.add(implementation.getOwner());arguments.add(implementation.getName());arguments.add(implementation.getDesc());
            arguments.add(direct?"":implementation);
            for(int index=3;index<original.length;index++)arguments.add(original[index]);
            site.bsm=BOOTSTRAP;site.bsmArgs=arguments.toArray();
        }
    }
    /** javac uses a small lambda body to collect varargs constructor-reference arguments. */
    private static MethodInsnNode creator(MethodNode method) {
        if(!method.name.startsWith("lambda$"))return null;
        MethodInsnNode constructor=null;int allocations=0;
        for(var instruction:method.instructions) {
            if(instruction instanceof TypeInsnNode type&&type.getOpcode()==NEW)allocations++;
            if(instruction instanceof InvokeDynamicInsnNode)return null;
            if(instruction instanceof MethodInsnNode call) {
                if(call.getOpcode()==INVOKESPECIAL&&call.name.equals("<init>")){if(constructor!=null)return null;constructor=call;}
                else if(!(call.owner.startsWith("java/lang/")&&Set.of("Boolean","Byte","Character","Short","Integer","Long","Float","Double").contains(call.owner.substring(10))
                        &&(call.name.equals("valueOf")||call.name.endsWith("Value"))))return null;
            }
        }
        return allocations==1?constructor:null;
    }
    private static String template(String caller,String name,String invoked,Object[] arguments,boolean alternative) {
        var interfaces=new LinkedHashSet<String>();interfaces.add(Type.getReturnType(invoked).getInternalName());
        var methods=new LinkedHashSet<Type>();methods.add((Type)arguments[0]);
        int flags=alternative?(Integer)arguments[3]:0,index=4;
        if((flags&2)!=0){int count=(Integer)arguments[index++];for(int n=0;n<count;n++)interfaces.add(((Type)arguments[index++]).getInternalName());}
        if((flags&4)!=0){int count=(Integer)arguments[index++];for(int n=0;n<count;n++)methods.add((Type)arguments[index++]);}
        boolean serializable=(flags&1)!=0;if(serializable)interfaces.add("java/io/Serializable");
        String type=caller+InjectedNames.PREFIX+"ConstructorReference$"+Integer.toHexString(Objects.hash(name,invoked,Arrays.deepHashCode(arguments)));
        var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        writer.visit(V17,ACC_FINAL|ACC_SYNTHETIC,type,null,"java/lang/Object",interfaces.toArray(String[]::new));
        int ordinal=0;for(Type ignored:methods)writer.visitField(ACC_PRIVATE|ACC_FINAL,InjectedNames.PREFIX+"target$"+ordinal++,"Ljava/lang/invoke/MethodHandle;",null,null).visitEnd();
        if(serializable){writer.visitField(ACC_PRIVATE|ACC_FINAL,InjectedNames.PREFIX+"metadata","L"+META+";",null,null).visitEnd();writer.visitField(ACC_PRIVATE|ACC_FINAL,InjectedNames.PREFIX+"captured","[Ljava/lang/Object;",null,null).visitEnd();}
        var constructor=writer.visitMethod(ACC_PRIVATE,"<init>","(L"+META+";[Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)V",null,null);constructor.visitCode();
        constructor.visitVarInsn(ALOAD,0);constructor.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        ordinal=0;for(Type ignored:methods){constructor.visitVarInsn(ALOAD,0);constructor.visitVarInsn(ALOAD,2);constructor.visitLdcInsn(ordinal);constructor.visitInsn(AALOAD);constructor.visitFieldInsn(PUTFIELD,type,InjectedNames.PREFIX+"target$"+ordinal++,"Ljava/lang/invoke/MethodHandle;");}
        if(serializable){constructor.visitVarInsn(ALOAD,0);constructor.visitVarInsn(ALOAD,1);constructor.visitFieldInsn(PUTFIELD,type,InjectedNames.PREFIX+"metadata","L"+META+";");constructor.visitVarInsn(ALOAD,0);constructor.visitVarInsn(ALOAD,3);constructor.visitFieldInsn(PUTFIELD,type,InjectedNames.PREFIX+"captured","[Ljava/lang/Object;");}
        constructor.visitInsn(RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();
        ordinal=0;for(Type method:methods) {
            var body=writer.visitMethod(ACC_PUBLIC|(ordinal==0?0:ACC_BRIDGE|ACC_SYNTHETIC),name,method.getDescriptor(),null,null);body.visitCode();
            body.visitVarInsn(ALOAD,0);body.visitFieldInsn(GETFIELD,type,InjectedNames.PREFIX+"target$"+ordinal++,"Ljava/lang/invoke/MethodHandle;");
            int local=1;for(Type parameter:method.getArgumentTypes()){body.visitVarInsn(parameter.getOpcode(ILOAD),local);local+=parameter.getSize();}
            body.visitMethodInsn(INVOKEVIRTUAL,"java/lang/invoke/MethodHandle","invokeExact",method.getDescriptor(),false);body.visitInsn(method.getReturnType().getOpcode(IRETURN));body.visitMaxs(0,0);body.visitEnd();
        }
        if(serializable) {
            var write=writer.visitMethod(ACC_PRIVATE|ACC_FINAL,"writeReplace","()Ljava/lang/Object;",null,null);write.visitCode();
            write.visitVarInsn(ALOAD,0);write.visitFieldInsn(GETFIELD,type,InjectedNames.PREFIX+"metadata","L"+META+";");write.visitVarInsn(ALOAD,0);write.visitFieldInsn(GETFIELD,type,InjectedNames.PREFIX+"captured","[Ljava/lang/Object;");
            write.visitMethodInsn(INVOKEVIRTUAL,META,"serialized","([Ljava/lang/Object;)Ljava/lang/invoke/SerializedLambda;",false);write.visitInsn(ARETURN);write.visitMaxs(0,0);write.visitEnd();
        }
        writer.visitEnd();return Base64.getEncoder().encodeToString(writer.toByteArray());
    }
}
