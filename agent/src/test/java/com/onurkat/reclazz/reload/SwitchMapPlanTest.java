/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SwitchMapPlanTest implements Opcodes {
    private static byte[] fixture(int version) throws Exception {
        try (var in = SwitchMapPlanTest.class.getResourceAsStream("/switchmaps/javac" + version + ".base64")) {
            return Base64.getMimeDecoder().decode(Objects.requireNonNull(in).readAllBytes());
        }
    }
    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    @ParameterizedTest @ValueSource(ints = {17, 21, 25})
    void recognizesActualCompilerOutput(int version) throws Exception {
        var parsed = SwitchMapPlan.parse(fixture(version));
        assertNull(parsed.refusal()); assertNotNull(parsed.plan());
        assertEquals("p/Svc$1", parsed.plan().holder()); assertEquals(2, parsed.plan().tables().size());
        var channel = parsed.plan().tables().stream().filter(t -> t.enumName().equals("p/Channel")).findFirst().orElseThrow();
        assertEquals(List.of(new SwitchMapPlan.Entry("WALLET", 1), new SwitchMapPlan.Entry("BANK", 2),
                new SwitchMapPlan.Entry("CARD", 3)), channel.entries());
    }
    @Test void refusesExtraInstructionBeforeAnyTableCanBeAccepted() throws Exception {
        ClassNode node = new ClassNode(); new ClassReader(fixture(21)).accept(node, 0);
        node.methods.get(0).instructions.insertBefore(node.methods.get(0).instructions.getLast(), new InsnNode(NOP));
        var parsed = SwitchMapPlan.parse(bytes(node)); assertNull(parsed.plan()); assertNotNull(parsed.refusal());
    }
    @Test void refusesOrdinaryStaticAlongsideTables() throws Exception {
        ClassNode node = new ClassNode(); new ClassReader(fixture(17)).accept(node, 0);
        node.fields.add(new FieldNode(ACC_STATIC, "ordinary", "I", null, null));
        assertNull(SwitchMapPlan.parse(bytes(node)).plan());
    }
    @Test void refusesMissingGuardAndWrongJump() throws Exception {
        ClassNode node = new ClassNode(); new ClassReader(fixture(25)).accept(node, 0);
        node.methods.get(0).tryCatchBlocks.remove(0);
        assertNull(SwitchMapPlan.parse(bytes(node)).plan());
        new ClassReader(fixture(25)).accept(node = new ClassNode(), 0);
        for (var insn : node.methods.get(0).instructions) if (insn instanceof JumpInsnNode jump) {
            jump.label = node.methods.get(0).tryCatchBlocks.get(0).handler; break;
        }
        assertNull(SwitchMapPlan.parse(bytes(node)).plan());
    }
    @Test void preflightRefusesReferencingOuterInEitherOrder() throws Exception {
        ClassNode node = new ClassNode(); new ClassReader(fixture(21)).accept(node, 0);
        node.fields.add(new FieldNode(ACC_STATIC, "ordinary", "I", null, null));
        ClassWriter outer = new ClassWriter(0); outer.visit(V17, ACC_PUBLIC, "p/Svc", null, "java/lang/Object", null);
        var method = outer.visitMethod(ACC_PUBLIC | ACC_STATIC, "map", "()[I", null, null);
        method.visitCode(); method.visitFieldInsn(GETSTATIC, "p/Svc$1", "$SwitchMap$p$Channel", "[I");
        method.visitInsn(ARETURN); method.visitMaxs(1, 0); method.visitEnd(); outer.visitEnd();
        for (boolean holderFirst : List.of(false, true)) {
            Map<String, byte[]> save = new LinkedHashMap<>();
            if (holderFirst) save.put("p.Svc$1", bytes(node));
            save.put("p.Svc", outer.toByteArray()); save.put("p.Svc$1", bytes(node));
            var result = SwitchMapPreflight.check(save);
            assertEquals(Set.of("p.Svc", "p.Svc$1"), result.refused().keySet()); assertTrue(result.plans().isEmpty());
        }
    }
}
