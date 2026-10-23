/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Turns an added constructor into a factory with the original local slots and delegation order. */
final class AddedConstructorGenerator implements Opcodes {
    private static final String MEMBER="com/onurkat/reclazz/bootstrap/MemberForge";
    static String name(String descriptor){return InjectedNames.PREFIX+"construct$"+InjectedNames.descHash(descriptor);}
    static String descriptor(String owner,String ctor){return "(L"+owner+";"+ctor.substring(1,ctor.length()-1)+"L"+owner+";";}

    static void emit(ClassNode source,MethodNode constructor,ClassWriter writer,MethodVisitor adapter) {
        var calls=new ArrayList<MethodInsnNode>();
        for(var insn:constructor.instructions)if(insn instanceof MethodInsnNode call)calls.add(call);
        var fieldInsns=new ArrayList<FieldInsnNode>();
        for(var insn:constructor.instructions)if(insn instanceof FieldInsnNode field)fieldInsns.add(field);
        Set<FieldInsnNode> preWrites=Collections.newSetFromMap(new IdentityHashMap<>());int[] fieldOrdinal={0};
        MethodInsnNode[] delegation={null};String[] unsupported={null};int[] ordinal={0};boolean[] initialized={false};
        constructor.accept(new AnalyzerAdapter(ASM9,source.name,constructor.access,"<init>",constructor.desc,null){
            @Override public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf){
                MethodInsnNode call=calls.get(ordinal[0]++);
                int size=Arrays.stream(Type.getArgumentTypes(desc)).mapToInt(Type::getSize).sum();
                if(opcode==INVOKESPECIAL && name.equals("<init>") && stack!=null && stack.size()>size
                        && stack.get(stack.size()-size-1).equals(UNINITIALIZED_THIS)) {delegation[0]=call;initialized[0]=true;}
                super.visitMethodInsn(opcode,owner,name,desc,itf);
            }
            @Override public void visitFieldInsn(int opcode,String owner,String name,String desc){
                FieldInsnNode field=fieldInsns.get(fieldOrdinal[0]++);
                int size=opcode==PUTFIELD?Type.getType(desc).getSize():0;
                if(!initialized[0] && (opcode==PUTFIELD || opcode==GETFIELD) && stack!=null && stack.size()>size
                        && stack.get(stack.size()-size-1).equals(UNINITIALIZED_THIS)) {
                    if(opcode==PUTFIELD)preWrites.add(field);else unsupported[0]="pre-super receiver field read";
                }
                super.visitFieldInsn(opcode,owner,name,desc);
            }
        });
        if(unsupported[0]!=null){
            adapter.visitCode();adapter.visitTypeInsn(NEW,"java/lang/UnsupportedOperationException");adapter.visitInsn(DUP);
            adapter.visitLdcInsn("Added constructor requires "+unsupported[0]+": "+source.name+constructor.desc);
            adapter.visitMethodInsn(INVOKESPECIAL,"java/lang/UnsupportedOperationException","<init>","(Ljava/lang/String;)V",false);
            adapter.visitInsn(ATHROW);adapter.visitMaxs(0,0);adapter.visitEnd();return;
        }
        Set<String> finalFields=new HashSet<>();
        for(var field:source.fields)if((field.access&ACC_FINAL)!=0)finalFields.add(field.name+":"+field.desc);
        int[] callIndex={0},fieldIndex={0};
        MethodVisitor translated=new MethodVisitor(ASM9,adapter){
            @Override public void visitParameter(String n,int a) { }
            @Override public AnnotationVisitor visitAnnotation(String d,boolean visible){return null;}
            @Override public AnnotationVisitor visitParameterAnnotation(int p,String d,boolean visible){return null;}
            @Override public AnnotationVisitor visitTypeAnnotation(int r,TypePath p,String d,boolean visible){return null;}
            @Override public void visitAnnotableParameterCount(int count,boolean visible) { }
            @Override public void visitFrame(int t,int nl,Object[] l,int ns,Object[] s) { }
            @Override public void visitMethodInsn(int opcode,String owner,String n,String desc,boolean itf){
                MethodInsnNode actual=calls.get(callIndex[0]++);
                if(actual!=delegation[0]){super.visitMethodInsn(opcode,owner,n,desc,itf);return;}
                Type[] args=Type.getArgumentTypes(desc);int[] locals=new int[args.length];int next=constructor.maxLocals;
                for(int i=0;i<args.length;i++){locals[i]=next;next+=args[i].getSize();}
                for(int i=args.length-1;i>=0;i--)super.visitVarInsn(args[i].getOpcode(ISTORE),locals[i]);
                super.visitInsn(POP); // The placeholder for uninitialized this.
                super.visitLdcInsn(Type.getObjectType(source.name));super.visitLdcInsn(Type.getObjectType(owner));super.visitLdcInsn(desc);
                super.visitLdcInsn(args.length);super.visitTypeInsn(ANEWARRAY,"java/lang/Object");
                for(int i=0;i<args.length;i++) {super.visitInsn(DUP);super.visitLdcInsn(i);super.visitVarInsn(args[i].getOpcode(ILOAD),locals[i]);
                    CompanionGenerator.CompanionMethodAdapter.box(mv,args[i]);super.visitInsn(AASTORE);}
                super.visitMethodInsn(INVOKESTATIC,MEMBER,"delegate","(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",false);
                super.visitTypeInsn(CHECKCAST,source.name);super.visitVarInsn(ASTORE,0);
            }
            @Override public void visitFieldInsn(int opcode,String owner,String n,String desc){
                boolean before=preWrites.contains(fieldInsns.get(fieldIndex[0]++));
                if(opcode==PUTFIELD && owner.equals(source.name) && (before || finalFields.contains(n+":"+desc))
                        && !source.fields.stream().anyMatch(f->f.name.equals(n) && (f.access&ACC_STATIC)!=0)) {
                    CompanionGenerator.CompanionMethodAdapter.box(mv,Type.getType(desc));
                    super.visitLdcInsn(Type.getObjectType(owner));super.visitLdcInsn(n);
                    super.visitMethodInsn(INVOKESTATIC,MEMBER,before?"beforeSuperField":"constructorField","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)V",false);
                } else super.visitFieldInsn(opcode,owner,n,desc);
            }
            @Override public void visitInsn(int opcode){
                if(opcode==RETURN){super.visitVarInsn(ALOAD,0);super.visitMethodInsn(INVOKESTATIC,MEMBER,"finishConstruction","()V",false);super.visitInsn(ARETURN);}
                else super.visitInsn(opcode);
            }
        };
        constructor.accept(translated);
    }
}
