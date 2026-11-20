/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Receipt bookkeeping tests; real native/virtual admission is verified by the enabling commits. */
class RecordConstructorReceiptTest {
    static class Loader extends ClassLoader {
        Class<?> define(byte[] raw){return defineClass("com.example.RecordReceipt",raw,0,raw.length);}
    }
    static byte[] record(boolean added,int constructorBody,int staticBody) {
        var w=new ClassWriter(0);String owner="com/example/RecordReceipt";
        w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|Opcodes.ACC_RECORD,owner,null,"java/lang/Record",null);
        w.visitRecordComponent("a","I",null).visitEnd();
        if(added)w.visitRecordComponent("b","Ljava/lang/String;",null).visitEnd();
        var ctor=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>",added?"(ILjava/lang/String;)V":"(I)V",null,null);
        ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Record","<init>","()V",false);
        ctor.visitLdcInsn(constructorBody);ctor.visitInsn(Opcodes.POP);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(1,added?3:2);ctor.visitEnd();
        var init=w.visitMethod(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);init.visitCode();init.visitLdcInsn(staticBody);init.visitInsn(Opcodes.POP);init.visitInsn(Opcodes.RETURN);init.visitMaxs(1,0);init.visitEnd();
        w.visitEnd();return w.toByteArray();
    }
    @Test void nativeReceiptReplacesTheWholePhysicalCanonicalDescriptorSet() {
        byte[] old=record(false,1,1),next=record(true,2,2);var loader=new Loader();
        // Model an already-admitted native schema with its retained pre-admission raw baseline.
        KeepBaseline.capture(loader,"com/example/RecordReceipt",old);Class<?> type=loader.define(next);
        assertTrue(KeepBaseline.constructorBodiesUnchanged(type,old));assertTrue(KeepBaseline.recordNativeSchemaApplied(type,next));
        assertTrue(KeepBaseline.constructorBodiesUnchanged(type,next));assertFalse(KeepBaseline.constructorBodiesUnchanged(type,old));
        assertFalse(KeepBaseline.constructorBodiesUnchanged(type,record(true,3,2)));
        assertTrue(KeepBaseline.staticInitializerUnchanged(type,next));assertFalse(KeepBaseline.staticInitializerUnchanged(type,record(true,2,3)));
    }
    @Test void virtualReceiptDoesNotOverwritePhysicalCtorProof() {
        byte[] old=record(false,1,1),next=record(true,2,1);var loader=new Loader();Class<?> type=loader.define(old);
        KeepBaseline.capture(loader,"com/example/RecordReceipt",old);
        assertTrue(KeepBaseline.recordVirtualSchemaApplied(type,next));
        assertTrue(KeepBaseline.constructorBodiesUnchanged(type,old));assertFalse(KeepBaseline.constructorBodiesUnchanged(type,next));
        assertTrue(KeepBaseline.recordLogicalConstructorBodiesUnchanged(type,next));
        assertFalse(KeepBaseline.recordLogicalConstructorBodiesUnchanged(type,record(true,3,1)));
        assertTrue(KeepBaseline.staticInitializerUnchanged(type,old));
        assertFalse(KeepBaseline.staticInitializerUnchanged(type,record(true,2,2)));
        assertFalse(KeepBaseline.recordNativeSchemaApplied(type,next),"a virtual shape is not a native admission receipt");
        assertTrue(KeepBaseline.recordLogicalConstructorBodiesUnchanged(type,next));
    }
    @Test void failedReceiptInputAdvancesNeitherProof() {
        byte[] old=record(false,1,1),next=record(true,2,1);var loader=new Loader();Class<?> type=loader.define(old);
        KeepBaseline.capture(loader,"com/example/RecordReceipt",old);assertTrue(KeepBaseline.recordVirtualSchemaApplied(type,next));
        assertFalse(KeepBaseline.recordVirtualSchemaApplied(type,new byte[]{0}));assertFalse(KeepBaseline.recordNativeSchemaApplied(type,new byte[]{0}));
        assertTrue(KeepBaseline.constructorBodiesUnchanged(type,old));assertTrue(KeepBaseline.recordLogicalConstructorBodiesUnchanged(type,next));
        assertFalse(KeepBaseline.recordVirtualSchemaApplied(String.class,next));
    }
}
