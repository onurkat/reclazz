/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.Predicate;

/** Rewrites canonical value-producing NEW/DUP pairs; never rewrites this/super delegation. */
public final class ConstructorCallRewriter implements Opcodes {
    private static final String MEMBER="com/onurkat/reclazz/bootstrap/MemberForge";
    private ConstructorCallRewriter() { }
    public static ClassVisitor visitor(ClassVisitor output,Predicate<String> watched) {
        return new ClassNode(ASM9){@Override public void visitEnd(){
            super.visitEnd();for(var method:methods)rewrite(name,method,watched);accept(output);
        }};
    }
    private record Pair(TypeInsnNode allocation,AbstractInsnNode duplicate,MethodInsnNode call) { }
    private static void rewrite(String owner,MethodNode method,Predicate<String> watched) {
        if((method.access&(ACC_ABSTRACT|ACC_NATIVE))!=0)return;
        var allocations=new ArrayList<TypeInsnNode>();var calls=new ArrayList<MethodInsnNode>();
        for(var insn:method.instructions){if(insn instanceof TypeInsnNode t && t.getOpcode()==NEW)allocations.add(t);if(insn instanceof MethodInsnNode m)calls.add(m);}
        if(allocations.isEmpty())return;
        Map<Object,TypeInsnNode> tokens=new IdentityHashMap<>();var pairs=new ArrayList<Pair>();int[] allocationIndex={0},callIndex={0};
        method.accept(new AnalyzerAdapter(ASM9,owner,method.access,method.name,method.desc,null){
            @Override public void visitTypeInsn(int opcode,String type){
                super.visitTypeInsn(opcode,type);
                if(opcode==NEW){var allocation=allocations.get(allocationIndex[0]++);if(stack!=null)tokens.put(stack.get(stack.size()-1),allocation);}
            }
            @Override public void visitMethodInsn(int opcode,String target,String name,String descriptor,boolean itf){
                var call=calls.get(callIndex[0]++);int size=Arrays.stream(Type.getArgumentTypes(descriptor)).mapToInt(Type::getSize).sum();
                if(opcode==INVOKESPECIAL && name.equals("<init>") && watched.test(target) && stack!=null && stack.size()>size){
                    Object token=stack.get(stack.size()-size-1);var allocation=tokens.get(token);
                    if(allocation!=null && allocation.desc.equals(target)){
                        var duplicate=allocation.getNext();while(duplicate!=null && duplicate.getOpcode()<0)duplicate=duplicate.getNext();
                        long aliases=stack.stream().filter(value->value==token).count();
                        if(duplicate!=null && duplicate.getOpcode()==DUP && aliases==2)pairs.add(new Pair(allocation,duplicate,call));
                    }
                }
                super.visitMethodInsn(opcode,target,name,descriptor,itf);
            }
        });
        for(var pair:pairs){
            InsnList init=new InsnList();init.add(new LdcInsnNode(Type.getObjectType(pair.call.owner)));
            init.add(new MethodInsnNode(INVOKESTATIC,MEMBER,"ensureInitialized","(Ljava/lang/Class;)V",false));
            method.instructions.insertBefore(pair.allocation,init);method.instructions.remove(pair.allocation);method.instructions.remove(pair.duplicate);
            Type[] args=Type.getArgumentTypes(pair.call.desc);int[] locals=new int[args.length];
            for(int i=0;i<args.length;i++){locals[i]=method.maxLocals;method.maxLocals+=args[i].getSize();}
            MethodNode replacement=new MethodNode();
            for(int i=args.length-1;i>=0;i--)replacement.visitVarInsn(args[i].getOpcode(ISTORE),locals[i]);
            replacement.visitMethodInsn(INVOKESTATIC,"java/lang/invoke/MethodHandles","lookup","()Ljava/lang/invoke/MethodHandles$Lookup;",false);
            replacement.visitLdcInsn(Type.getObjectType(pair.call.owner));replacement.visitLdcInsn(pair.call.desc);
            replacement.visitLdcInsn(args.length);replacement.visitTypeInsn(ANEWARRAY,"java/lang/Object");
            for(int i=0;i<args.length;i++){
                replacement.visitInsn(DUP);replacement.visitLdcInsn(i);replacement.visitVarInsn(args[i].getOpcode(ILOAD),locals[i]);box(replacement,args[i]);replacement.visitInsn(AASTORE);
            }
            replacement.visitMethodInsn(INVOKESTATIC,MEMBER,"invokeConstructor","(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",false);
            replacement.visitTypeInsn(CHECKCAST,pair.call.owner);
            method.instructions.insertBefore(pair.call,replacement.instructions);method.instructions.remove(pair.call);
        }
        // Existing frames describe the original uninitialized allocation values.
        if(!pairs.isEmpty())for(var insn:method.instructions.toArray())if(insn instanceof FrameNode)method.instructions.remove(insn);
    }
    private static void box(MethodVisitor out,Type type){
        String wrapper=switch(type.getSort()){case Type.BOOLEAN->"Boolean";case Type.BYTE->"Byte";case Type.CHAR->"Character";case Type.SHORT->"Short";case Type.INT->"Integer";case Type.LONG->"Long";case Type.FLOAT->"Float";case Type.DOUBLE->"Double";default->null;};
        if(wrapper!=null)out.visitMethodInsn(INVOKESTATIC,"java/lang/"+wrapper,"valueOf","("+type.getDescriptor()+")Ljava/lang/"+wrapper+";",false);
    }
}
