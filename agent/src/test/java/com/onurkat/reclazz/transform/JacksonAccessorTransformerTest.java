/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class JacksonAccessorTransformerTest {
    private static byte[] accessor(String owner) {
        ClassWriter w=new ClassWriter(0);
        w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);
        MethodVisitor m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"accessor",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;",
                null,new String[]{"java/lang/IllegalAccessException"});
        m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitVarInsn(Opcodes.ALOAD,1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/invoke/MethodHandles$Lookup","unreflect",
                "(Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;",false);
        m.visitInsn(Opcodes.ARETURN);m.visitMaxs(2,2);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    @Test void redirectAppliesToEveryDatabindUnreflectSite() {
        String name="tools/jackson/databind/x/AccessorWithoutHolderName";
        byte[] changed=new JacksonAccessorTransformer().transform(null,name,null,null,accessor(name));
        assertNotNull(changed);
        ClassNode node=new ClassNode();new ClassReader(changed).accept(node,0);
        MethodInsnNode call=null;
        for(AbstractInsnNode i:node.methods.get(0).instructions.toArray())if(i instanceof MethodInsnNode method)call=method;
        assertNotNull(call);assertEquals(Opcodes.INVOKESTATIC,call.getOpcode());
        assertEquals("com/onurkat/reclazz/bootstrap/JacksonBridge",call.owner);assertEquals("unreflect",call.name);
        assertEquals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;",call.desc);
    }
    @Test void nonDatabindClassIsNotRewritten() {
        String name="com/example/OrdinaryAccessor";
        assertNull(new JacksonAccessorTransformer().transform(null,name,null,null,accessor(name)));
    }
    @Test void jacksonTwoUnreflectCoverageStaysUnchanged() {
        String name="com/fasterxml/jackson/databind/x/Accessor";
        assertNull(new JacksonAccessorTransformer().transform(null,name,null,null,accessor(name)));
    }
}
