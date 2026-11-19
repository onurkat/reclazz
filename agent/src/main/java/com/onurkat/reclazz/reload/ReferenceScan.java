/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.*;
import java.util.*;

/** Byte-only linkage evidence. No class loading, reflection or publication. */
public final class ReferenceScan {
    private ReferenceScan() { }
    public record Member(String owner,String name,String descriptor,boolean field) { }
    public record References(Set<Member> members,Set<String> literals,boolean reflection) {
        public References { members=Set.copyOf(members);literals=Set.copyOf(literals); }
    }
    public static References read(byte[] bytes) {
        Set<Member> members=new LinkedHashSet<>();Set<String> literals=new LinkedHashSet<>();boolean[] reflection={false};
        class Constants {
            void read(Object value) {
                if(value instanceof Handle handle) {
                    int tag=handle.getTag();members.add(new Member(handle.getOwner().replace('/','.'),handle.getName(),handle.getDesc(),tag>=Opcodes.H_GETFIELD&&tag<=Opcodes.H_PUTSTATIC));
                } else if(value instanceof ConstantDynamic dynamic) {
                    read(dynamic.getBootstrapMethod());for(int i=0;i<dynamic.getBootstrapMethodArgumentCount();i++)read(dynamic.getBootstrapMethodArgument(i));
                } else if(value instanceof Type type && type.getSort()==Type.OBJECT)literals.add(type.getClassName());
            }
        }
        Constants constants=new Constants();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitFieldInsn(int opcode,String owner,String name,String desc) {members.add(new Member(owner.replace('/','.'),name,desc,true));}
                    @Override public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf) {
                        members.add(new Member(owner.replace('/','.'),name,desc,false));
                        if(owner.equals("java/lang/Class")&&Set.of("getConstructor","getDeclaredConstructor","getMethod","getDeclaredMethod","getField","getDeclaredField","getRecordComponents").contains(name))reflection[0]=true;
                    }
                    @Override public void visitInvokeDynamicInsn(String name,String descriptor,Handle bootstrap,Object... args) {constants.read(bootstrap);for(Object arg:args)constants.read(arg);}
                    @Override public void visitLdcInsn(Object value) {constants.read(value);}
                };
            }
        },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        return new References(members,literals,reflection[0]);
    }
}
