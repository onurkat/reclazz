/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordCodecTransformerTest {
    private static byte[] fixture(String owner, String slot) {
        ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, slot, "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(1,1); ctor.visitEnd();
        MethodVisitor read = writer.visitMethod(Opcodes.ACC_PUBLIC, "read", "()Ljava/lang/Object;", null, null); read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD,0); read.visitFieldInsn(Opcodes.GETFIELD,owner,slot,"Ljava/lang/Object;");
        read.visitInsn(Opcodes.ARETURN); read.visitMaxs(1,1); read.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    @Test void bothJacksonNamespacesGuardEachSnapshotAndTrackConstructorsWithoutSchemaChanges() {
        for (String prefix : new String[]{"com/fasterxml/jackson/databind/", "tools/jackson/databind/"}) {
            for (String[] spec : new String[][]{{"ObjectReader","_rootDeserializer"},{"ObjectWriter","_prefetch"},{"MappingIterator","_deserializer"}}) {
                String owner = prefix + spec[0]; byte[] bytes = fixture(owner,spec[1]);
                byte[] changed = new RecordCodecTransformer().transform(null,owner,Object.class,null,bytes);
                ClassNode node = new ClassNode(); new ClassReader(changed).accept(node,0);
                assertEquals(1,node.fields.size()); assertEquals(2,node.methods.size());
                MethodNode ctor = node.methods.get(0), read = node.methods.get(1);
                assertEquals(1,java.util.Arrays.stream(ctor.instructions.toArray()).filter(i -> i instanceof MethodInsnNode m && m.name.equals("track")).count());
                assertEquals(1,java.util.Arrays.stream(read.instructions.toArray()).filter(i -> i instanceof MethodInsnNode m && m.name.equals("current") && m.owner.endsWith("/RecordCodecs")).count());
                assertEquals(Opcodes.DUP,read.instructions.get(1).getOpcode());
                assertEquals(Opcodes.CHECKCAST,read.instructions.get(4).getOpcode());
            }
        }
    }
    @Test void mapperTrackingAndUnrelatedClassesStaySeparate() {
        String mapper="com/fasterxml/jackson/databind/ObjectMapper";
        byte[] changed=new RecordCodecTransformer().transform(null,mapper,null,null,fixture(mapper,"_prefetch"));
        ClassNode node=new ClassNode();new ClassReader(changed).accept(node,0);
        assertTrue(java.util.Arrays.stream(node.methods.get(0).instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode m && m.owner.endsWith("/JacksonMapperRegistry")));
        assertEquals(1,java.util.Arrays.stream(node.methods.get(1).instructions.toArray()).filter(i->i instanceof MethodInsnNode m && m.owner.endsWith("/JacksonMapperRegistry") && m.name.equals("track")).count());
        assertFalse(java.util.Arrays.stream(node.methods.get(1).instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode m && m.owner.endsWith("/RecordCodecs")));
        for(String owner:new String[]{"com/example/ObjectWriter","tools/jackson/databind/util/ObjectReader"})
            assertNull(new RecordCodecTransformer().transform(null,owner,null,null,fixture(owner,"_prefetch")));
    }
}
