/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

/** R35: library caches are cleared only when a reload adds or removes members, never for a body-only save. */
class LibraryCachesTest {
    public static class Sample {
        private String name = "a";
        public String getName() { return name; }
    }

    private static byte[] bytes() throws Exception {
        try (var in = Sample.class.getResourceAsStream("LibraryCachesTest$Sample.class")) { return in.readAllBytes(); }
    }

    @Test void theSameShapeWithAnotherBodyIsNoMemberChange() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes()).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                    @Override public void visitLdcInsn(Object value) { super.visitLdcInsn("a".equals(value) ? "b" : value); }
                };
            }
        }, 0);
        assertFalse(LibraryCaches.membersChange(Sample.class, writer.toByteArray()));
    }

    @Test void anAddedMethodOrFieldIsAMemberChange() throws Exception {
        ClassWriter method = new ClassWriter(0);
        new ClassReader(bytes()).accept(new ClassVisitor(Opcodes.ASM9, method) {
            @Override public void visitEnd() {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "getExtra", "()Ljava/lang/String;", null, null);
                mv.visitCode(); mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(1, 1); mv.visitEnd();
                super.visitEnd();
            }
        }, 0);
        assertTrue(LibraryCaches.membersChange(Sample.class, method.toByteArray()));
        ClassWriter field = new ClassWriter(0);
        new ClassReader(bytes()).accept(new ClassVisitor(Opcodes.ASM9, field) {
            @Override public void visitEnd() { super.visitField(Opcodes.ACC_PRIVATE, "extra", "Ljava/lang/String;", null, null).visitEnd(); super.visitEnd(); }
        }, 0);
        assertTrue(LibraryCaches.membersChange(Sample.class, field.toByteArray()));
    }
}
