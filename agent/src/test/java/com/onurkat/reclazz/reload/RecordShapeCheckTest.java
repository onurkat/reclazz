/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordShapeCheckTest {
    record Pair(String text,int count) { }
    static class Plain { }
    static byte[] shape(boolean record,String... components) {
        ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|(record?Opcodes.ACC_RECORD:0),"p/Probe",null,record?"java/lang/Record":"java/lang/Object",null);
        for(int i=0;i<components.length;i+=2)w.visitRecordComponent(components[i],components[i+1],null).visitEnd();
        w.visitEnd();return w.toByteArray();
    }
    @Test void componentAddedRemovedRetypedReordered() {
        assertFalse(RecordShapeCheck.changed(Pair.class,shape(true,"text","Ljava/lang/String;","count","I")));
        assertTrue(RecordShapeCheck.changed(Pair.class,shape(true,"text","Ljava/lang/String;","count","I","marker","Ljava/lang/String;")));
        assertTrue(RecordShapeCheck.changed(Pair.class,shape(true,"text","Ljava/lang/String;")));
        assertTrue(RecordShapeCheck.changed(Pair.class,shape(true,"text","I","count","I")));
        assertTrue(RecordShapeCheck.changed(Pair.class,shape(true,"count","I","text","Ljava/lang/String;")));
    }
    @Test void recordFlagBothDirections() {
        assertTrue(RecordShapeCheck.changed(Pair.class,shape(false)));
        assertTrue(RecordShapeCheck.changed(Plain.class,shape(true)));
        assertFalse(RecordShapeCheck.changed(Plain.class,shape(false)));
    }
    @Test void bodyOnlyEditUnchanged() {
        var node=new org.objectweb.asm.tree.ClassNode();new ClassReader(shape(true,"text","Ljava/lang/String;","count","I")).accept(node,0);
        var method=new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC,"body","()I",null,null);method.visitCode();method.visitInsn(Opcodes.ICONST_2);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(1,1);method.visitEnd();node.methods.add(method);
        ClassWriter writer=new ClassWriter(0);node.accept(writer);assertFalse(RecordShapeCheck.changed(Pair.class,writer.toByteArray()));
    }
}
