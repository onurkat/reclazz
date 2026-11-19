/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceScanTest {
    static byte[] code(java.util.function.Consumer<MethodVisitor> body) {
        var writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"p/Caller",null,"java/lang/Object",null);
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"call","()V",null,null);method.visitCode();body.accept(method);method.visitInsn(Opcodes.RETURN);method.visitMaxs(5,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    static Handle constructor(){return new Handle(Opcodes.H_NEWINVOKESPECIAL,"p/Greeting","<init>","(Ljava/lang/String;Ljava/lang/String;)V",false);}
    static Handle bootstrap(){return new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory","()Ljava/lang/Object;",false);}
    @Test void methodrefFieldref() {
        var refs=ReferenceScan.read(code(m->{m.visitFieldInsn(Opcodes.GETFIELD,"p/Greeting","marker","Ljava/lang/String;");m.visitMethodInsn(Opcodes.INVOKESPECIAL,"p/Greeting","<init>","(Ljava/lang/String;Ljava/lang/String;)V",false);}));
        assertTrue(refs.members().contains(new ReferenceScan.Member("p.Greeting","marker","Ljava/lang/String;",true)));
        assertTrue(refs.members().contains(new ReferenceScan.Member("p.Greeting","<init>","(Ljava/lang/String;Ljava/lang/String;)V",false)));
    }
    @Test void constructorReferenceHandle() {
        var refs=ReferenceScan.read(code(m->m.visitInvokeDynamicInsn("apply","()Ljava/util/function/BiFunction;",bootstrap(),constructor())));
        assertTrue(refs.members().stream().anyMatch(r->r.owner().equals("p.Greeting")&&r.name().equals("<init>")));
    }
    @Test void objectMethodsBootstrap() {
        var refs=ReferenceScan.read(code(m->m.visitInvokeDynamicInsn("toString","(Lp/Greeting;)Ljava/lang/String;",new Handle(Opcodes.H_INVOKESTATIC,"java/lang/runtime/ObjectMethods","bootstrap","()Ljava/lang/Object;",false),Type.getObjectType("p/Greeting"),"text;marker",new Handle(Opcodes.H_GETFIELD,"p/Greeting","marker","Ljava/lang/String;",false))));
        assertTrue(refs.members().contains(new ReferenceScan.Member("p.Greeting","marker","Ljava/lang/String;",true)));
        assertTrue(refs.literals().contains("p.Greeting"));
    }
    @Test void condyArgument() {
        var nested=new ConstantDynamic("nested","Ljava/lang/Object;",bootstrap(),constructor());
        var refs=ReferenceScan.read(code(m->m.visitLdcInsn(new ConstantDynamic("outer","Ljava/lang/Object;",bootstrap(),nested))));
        assertTrue(refs.members().stream().anyMatch(r->r.owner().equals("p.Greeting")&&r.name().equals("<init>")));
    }
    @Test void reflectionWithClassLiteral() {
        var refs=ReferenceScan.read(code(m->{m.visitLdcInsn(Type.getObjectType("p/Greeting"));m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/Class","getDeclaredConstructor","([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",false);}));
        assertTrue(refs.reflection());assertEquals(Set.of("p.Greeting"),refs.literals());
    }
    @Test void scanDoesNotLoadAbsentClass() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();ClassLoader sentinel=new ClassLoader(null){@Override public Class<?> loadClass(String n)throws ClassNotFoundException{calls.incrementAndGet();throw new ClassNotFoundException(n);}};
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        try {Thread.currentThread().setContextClassLoader(sentinel);assertFalse(ReferenceScan.read(code(m->m.visitLdcInsn(constructor()))).members().isEmpty());assertEquals(0,calls.get());}
        finally {Thread.currentThread().setContextClassLoader(previous);}
    }
}
