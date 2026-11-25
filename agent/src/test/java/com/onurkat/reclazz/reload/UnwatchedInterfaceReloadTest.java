/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.transform.RawClassSchema;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.*;

class UnwatchedInterfaceReloadTest {
    interface Api { }

    @Test void refusesAdditionsBeforePreparingAnUnwatchedInterface() throws Exception {
        byte[] original;
        try (var input = Api.class.getResourceAsStream("/" + Type.getInternalName(Api.class) + ".class")) {
            original = input.readAllBytes();
        }
        var writer = new ClassWriter(0);
        new ClassReader(original).accept(writer, 0);
        var method = writer.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, "added", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        var error = assertThrows(IllegalArgumentException.class, () -> InterfaceMethodReload.prepare(
                Api.class, null, writer.toByteArray(), RawClassSchema.read(original), name -> false,
                InterfaceMethodReload.history(Api.class)));
        assertTrue(error.getMessage().contains("interface is outside the watched roots"), error::getMessage);
    }
}
