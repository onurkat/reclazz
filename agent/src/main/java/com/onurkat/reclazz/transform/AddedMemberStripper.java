/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/**
 * Removes members a reload added, leaving the rest of the new class as it is.
 *
 * The point is to produce something the JVM will accept redefining. A class
 * that gained a field or a method cannot be redefined, the JVM rejects any
 * schema change, so the companion engine keeps the new members outside the
 * loaded class and dispatches to them. But that left the loaded class holding
 * its *original* constructor bodies, because constructors cannot be moved to a
 * companion: their super() chains have to run on the real class.
 *
 * The consequence was quiet and wrong. Add a field with an initialiser, and an
 * object created after the reload still got the default value, because the
 * constructor that ran was the one compiled before the field existed. Not just
 * pre-existing instances, which is a documented and unavoidable limit, but new
 * ones, which is not.
 *
 * Stripping the added members gives a class whose shape matches what is loaded
 * and whose method bodies are the new ones. The registered transformer then
 * rewrites access to the added members onto the companion store during the
 * redefine, exactly as it does at load time, so the new constructor's
 * assignment to a new field lands where reads of that field will look for it.
 */
public final class AddedMemberStripper {

    private AddedMemberStripper() {
    }

    /**
     * @param addedFields  keys as {@code name:descriptor}
     * @param addedMethods keys as {@code name:descriptor}
     */
    /**
     * Reshapes the new bytecode into something the JVM will accept redefining.
     *
     * A redefinition has to hand back the same members the loaded class has:
     * nothing added and nothing missing. Stripping what the reload added was
     * only half of it. When a reload also removed a method, the payload was
     * short one member, the JVM refused the whole redefinition, and the
     * constructor bodies this class exists to deliver went with it. Measured on
     * a Spring Boot application: a field added by the same reload and assigned
     * in the constructor came back null on every newly created bean.
     *
     * Removed members come back as stubs with their original modifiers, and
     * whether a stub body is reached was measured rather than assumed. When
     * this payload is redefined successfully, the transformer renames the
     * stub over the removed method's {@code __reclazz$v0$...} copy, so
     * existing callers meet the stub's UnsupportedOperationException. When
     * the class carries members added by an earlier reload, the redefinition
     * is refused and callers keep dispatching to the implementation they
     * were linked to. The reloader's removed-method warning states whichever
     * of the two actually occurred.
     */
    public static byte[] reshape(byte[] newBytecode,
                                 java.util.Set<String> addedFields,
                                 java.util.Set<String> addedMethods,
                                 java.util.List<com.onurkat.reclazz.transform.TransformContext.MethodSig> removedMethods,
                                 java.util.List<com.onurkat.reclazz.transform.TransformContext.FieldSig> removedFields) {
        byte[] stripped = strip(newBytecode, addedFields, addedMethods);
        if (removedMethods.isEmpty() && removedFields.isEmpty()) return stripped;

        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(stripped);
        org.objectweb.asm.ClassWriter writer =
                new org.objectweb.asm.ClassWriter(reader, 0);
        reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                for (var f : removedFields) {
                    org.objectweb.asm.FieldVisitor fv =
                            writer.visitField(f.access(), f.name(), f.descriptor(), null, null);
                    if (fv != null) fv.visitEnd();
                }
                for (var m : removedMethods) {
                    if ("<init>".equals(m.name()) || "<clinit>".equals(m.name())) continue;
                    writeStub(writer, m);
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    /** A body that says what happened, for the case it is ever reached. */
    private static void writeStub(org.objectweb.asm.ClassWriter writer,
                                  com.onurkat.reclazz.transform.TransformContext.MethodSig m) {
        org.objectweb.asm.MethodVisitor mv =
                writer.visitMethod(m.access(), m.name(), m.descriptor(), null, null);
        if (mv == null) return;
        if ((m.access() & (org.objectweb.asm.Opcodes.ACC_ABSTRACT
                | org.objectweb.asm.Opcodes.ACC_NATIVE)) != 0) {
            mv.visitEnd();
            return;
        }
        mv.visitCode();
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(org.objectweb.asm.Opcodes.DUP);
        mv.visitLdcInsn("Reclazz: " + m.name() + " was removed by a reload");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.ATHROW);
        mv.visitMaxs(3, 1 + org.objectweb.asm.Type.getArgumentTypes(m.descriptor()).length);
        mv.visitEnd();
    }

    public static byte[] strip(byte[] newBytecode, Set<String> addedFields, Set<String> addedMethods) {
        if ((addedFields == null || addedFields.isEmpty())
                && (addedMethods == null || addedMethods.isEmpty())) {
            return newBytecode;
        }

        ClassReader reader = new ClassReader(newBytecode);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new Stripper(writer, addedFields, addedMethods), 0);
        return writer.toByteArray();
    }

    private static final class Stripper extends ClassVisitor {
        private final Set<String> fields;
        private final Set<String> methods;

        Stripper(ClassVisitor cv, Set<String> fields, Set<String> methods) {
            super(Opcodes.ASM9, cv);
            this.fields = fields == null ? Set.of() : fields;
            this.methods = methods == null ? Set.of() : methods;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (fields.contains(name + ":" + descriptor)) {
                return null;
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            // Constructors are the reason this class exists: they must survive
            // so their new bodies reach the loaded class. Only members the
            // reload introduced are dropped.
            if (!"<init>".equals(name) && !"<clinit>".equals(name)
                    && methods.contains(name + ":" + descriptor)) {
                return null;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }
}
