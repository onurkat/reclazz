/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class GeneratedClassCompatibilityTest {
    private byte[] dto(int constant, String getter, Consumer<ClassWriter> added) {
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, "com/example/Dto", null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "LIMIT", "I", null, constant).visitEnd();
        if (getter != null) w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getName", getter, null, null).visitEnd();
        added.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }

    private byte[] original() { return dto(7, "()Ljava/lang/String;", w -> {}); }

    @Test void aNewPropertyPreservesConsumerDeclarations() {
        byte[] after = dto(7, "()Ljava/lang/String;", w -> {
            w.visitField(ACC_PRIVATE, "count", "I", null, null).visitEnd();
            w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getCount", "()I", null, null).visitEnd();
        });
        assertTrue(GeneratedClassCompatibility.preservesDeclarations(original(), after));
    }

    @Test void changingAnInlinedConstantRequiresTheFullCompiler() {
        assertFalse(GeneratedClassCompatibility.preservesDeclarations(original(), dto(8, "()Ljava/lang/String;", w -> {})));
    }

    @Test void changingOrRemovingTheGetterRequiresTheFullCompiler() {
        assertFalse(GeneratedClassCompatibility.preservesDeclarations(original(), dto(7, "()I", w -> {})));
        assertFalse(GeneratedClassCompatibility.preservesDeclarations(original(), dto(7, null, w -> {})));
    }

    @Test void anOverloadOfAnExistingMethodRequiresConsumerResolution() {
        byte[] after = dto(7, "()Ljava/lang/String;", w ->
                w.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getName", "(I)Ljava/lang/String;", null, null).visitEnd());
        assertFalse(GeneratedClassCompatibility.preservesDeclarations(original(), after));
    }
}
