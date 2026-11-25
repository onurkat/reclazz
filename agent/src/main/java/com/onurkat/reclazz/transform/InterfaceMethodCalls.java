/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import java.util.Set;
import java.util.function.Predicate;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Interface calls keep their interface owner; the bootstrap verifies caller access. */
public final class InterfaceMethodCalls {
    private static final String BRIDGE = "com/onurkat/reclazz/bootstrap/InterfaceCalls";
    private static final String PREFIX = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;";
    private static final Handle LINK = new Handle(Opcodes.H_INVOKESTATIC,BRIDGE,"link",
            PREFIX+"Ljava/lang/Class;I)Ljava/lang/invoke/CallSite;",false);
    private static final Handle REFERENCE = new Handle(Opcodes.H_INVOKESTATIC,BRIDGE,"reference",
            PREFIX+"Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;II[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",false);

    private InterfaceMethodCalls() { }

    public static byte[] rewrite(byte[] input, Predicate<String> watched, Set<String> privateInstance) {
        var reader = new ClassReader(input);
        boolean referencesInterface = false;
        for (int i = 1; i < reader.getItemCount(); i++) {
            int item = reader.getItem(i);
            if (item != 0 && reader.readByte(item - 1) == 11) { // CONSTANT_InterfaceMethodref
                referencesInterface = true;
                break;
            }
        }
        if (!referencesInterface) return input;
        var node = new ClassNode();
        reader.accept(node,0);
        boolean changed = false;
        for (MethodNode method : node.methods) changed |= rewrite(method,node.name,watched,privateInstance);
        if (!changed) return input;
        var out = new ClassWriter(0);
        node.accept(out);
        return out.toByteArray();
    }

    public static boolean rewrite(MethodNode method, String owner, Predicate<String> watched, Set<String> privateInstance) {
        boolean changed=false;
        for (var insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.itf && watched.test(call.owner)) {
                boolean statik=call.getOpcode()==Opcodes.INVOKESTATIC;
                if (!statik && !(owner.equals(call.owner) && privateInstance.contains(call.name+call.desc))) continue;
                String type=statik?call.desc:"(L"+call.owner+";"+call.desc.substring(1);
                method.instructions.set(call,new InvokeDynamicInsnNode(call.name,type,LINK,Type.getObjectType(call.owner),statik?1:0));
                changed=true;
            } else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    && indy.bsmArgs.length>=3 && indy.bsmArgs[1] instanceof Handle impl && impl.isInterface()
                    && watched.test(impl.getOwner())) {
                boolean statik=impl.getTag()==Opcodes.H_INVOKESTATIC;
                if (!statik && !(owner.equals(impl.getOwner()) && privateInstance.contains(impl.getName()+impl.getDesc()))) continue;
                Object[] args=new Object[indy.bsmArgs.length+4];
                args[0]=Type.getObjectType(impl.getOwner()); args[1]=impl.getName(); args[2]=Type.getMethodType(impl.getDesc());
                args[3]=statik?1:0; args[4]=indy.bsm.getName().equals("altMetafactory")?1:0;
                args[5]=indy.bsmArgs[0];
                System.arraycopy(indy.bsmArgs,2,args,6,indy.bsmArgs.length-2);
                indy.bsm=REFERENCE; indy.bsmArgs=args; changed=true;
            }
        }
        return changed;
    }
}
