/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.Arrays;

/** Companion input only: replace javac's generated record methods while preserving custom bodies. */
public final class RecordObjectMethodsAdapter {
    private RecordObjectMethodsAdapter() { }
    public static byte[] rewrite(byte[] incoming) {
        var reader=new ClassReader(incoming);var source=new ClassNode();reader.accept(source,0);
        if((source.access&Opcodes.ACC_RECORD)==0)return incoming;
        boolean changed=false;
        for(var method:source.methods) {
            boolean equal=method.name.equals("equals")&&method.desc.equals("(Ljava/lang/Object;)Z");
            boolean hash=method.name.equals("hashCode")&&method.desc.equals("()I");
            boolean text=method.name.equals("toString")&&method.desc.equals("()Ljava/lang/String;");
            if(!(equal||hash||text)||(method.access&Opcodes.ACC_STATIC)!=0)continue;
            var code=Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
            boolean marker=code.stream().anyMatch(i->i instanceof InvokeDynamicInsnNode indy
                    &&indy.bsm.getOwner().equals("java/lang/runtime/ObjectMethods")&&indy.bsm.getName().equals("bootstrap"));
            if(!marker)continue;
            int indyIndex=equal?2:1;String result=text?"Ljava/lang/String;":equal?"Z":"I";
            boolean generated=code.size()==(equal?4:3)&&code.get(0) instanceof VarInsnNode receiver
                    &&receiver.getOpcode()==Opcodes.ALOAD&&receiver.var==0
                    &&(!equal||code.get(1) instanceof VarInsnNode other&&other.getOpcode()==Opcodes.ALOAD&&other.var==1)
                    &&code.get(indyIndex) instanceof InvokeDynamicInsnNode indy&&indy.name.equals(method.name)
                    &&indy.bsm.getTag()==Opcodes.H_INVOKESTATIC&&indy.bsm.getOwner().equals("java/lang/runtime/ObjectMethods")
                    &&indy.bsm.getName().equals("bootstrap")
                    &&indy.desc.equals("(L"+source.name+";"+(equal?"Ljava/lang/Object;":"")+")"+result)
                    &&code.get(code.size()-1).getOpcode()==(text?Opcodes.ARETURN:Opcodes.IRETURN);
            if(!generated)throw new IllegalArgumentException("Unrecognized record ObjectMethods body: "+method.name);
            method.instructions.clear();method.tryCatchBlocks.clear();
            if(method.localVariables!=null)method.localVariables.clear();
            method.visibleLocalVariableAnnotations=null;method.invisibleLocalVariableAnnotations=null;
            var out=method.instructions;
            out.add(new VarInsnNode(Opcodes.ALOAD,0));
            if(equal)out.add(new VarInsnNode(Opcodes.ALOAD,1));
            out.add(new InvokeDynamicInsnNode(method.name,"(L"+source.name+";"+(equal?"Ljava/lang/Object;":"")+")"+result,
                    new Handle(Opcodes.H_INVOKESTATIC,"com/onurkat/reclazz/bootstrap/RecordObjects","bootstrap",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/CallSite;",false),
                    Type.getObjectType(source.name)));
            out.add(new InsnNode(text?Opcodes.ARETURN:Opcodes.IRETURN));
            method.maxStack=equal?4:3;method.maxLocals=equal?2:1;changed=true;
        }
        if(!changed)return incoming;
        var writer=new ClassWriter(reader,0);source.accept(writer);return writer.toByteArray();
    }
}
