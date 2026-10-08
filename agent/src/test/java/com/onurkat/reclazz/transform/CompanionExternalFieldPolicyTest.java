/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import com.onurkat.reclazz.reload.CompanionGenerator;
import com.onurkat.reclazz.reload.StructuralAnalyzer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class CompanionExternalFieldPolicyTest extends TransformTestBase {
    @Test
    void externalInstanceFieldsUseWatchedDispatchWithoutChangingOtherInstructions() {
        byte[] bytes = compile(
                new SourceFile("Foreign", "public class Foreign { public int value; public int __reclazz$marker; public static int shared; }"),
                new SourceFile("Plain", "public class Plain { public int value; }"),
                new SourceFile("Probe", """
                        public class Probe {
                            public int own;
                            public int run(Foreign other, Plain plain) {
                                other.value = 4; own = 5;
                                return other.value + other.__reclazz$marker + plain.value + own + Foreign.shared;
                            }
                        }
                        """)).get("Probe");
        ClassNode original = node(bytes);
        var before = new TransformContext.ClassMetadata(
                original.methods.stream().map(m -> new TransformContext.MethodSig(m.name, m.desc, m.access)).toList(),
                original.fields.stream().map(f -> new TransformContext.FieldSig(f.name, f.desc, f.access)).toList(),
                0, original.superName);
        var diff = StructuralAnalyzer.analyze(before, bytes);
        TransformContext context = new TransformContext();
        context.addWatched("Probe"); context.addWatched("Foreign");
        MethodNode live = method(CompanionGenerator.generate("Probe", bytes, diff, 1, Set.of(), Set.of(), context).getBytecode());
        List<String> fields = new ArrayList<>();
        List<String> sites = new ArrayList<>();
        for (var instruction : live.instructions) {
            if (instruction instanceof FieldInsnNode f) fields.add(f.owner + "." + f.name + ":" + f.getOpcode());
            if (instruction instanceof InvokeDynamicInsnNode site
                    && (site.bsm.getName().equals("bootstrapFieldGet") || site.bsm.getName().equals("bootstrapFieldSet"))) {
                assertEquals("com/onurkat/reclazz/bootstrap/ReclazzBootstrap", site.bsm.getOwner());
                assertEquals("Foreign", site.bsmArgs[0]);
                sites.add(site.bsm.getName() + ":" + site.desc);
            }
        }
        assertEquals(List.of("bootstrapFieldSet:(LForeign;I)V", "bootstrapFieldGet:(LForeign;)I"), sites);
        assertEquals(Set.of("Probe.own:" + PUTFIELD, "Probe.own:" + GETFIELD,
                "Foreign.__reclazz$marker:" + GETFIELD, "Plain.value:" + GETFIELD,
                "Foreign.shared:" + GETSTATIC), Set.copyOf(fields));
        assertEquals(5, fields.size());
        MethodNode legacy = method(CompanionGenerator.generate("Probe", bytes, diff, 2, Set.of(), Set.of()).getBytecode());
        assertEquals(7, java.util.stream.StreamSupport.stream(legacy.instructions.spliterator(), false)
                .filter(i -> i instanceof FieldInsnNode).count(), "context-free callers retain their prior bytecode policy");
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    private static MethodNode method(byte[] bytes) {
        return node(bytes).methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
    }
}
