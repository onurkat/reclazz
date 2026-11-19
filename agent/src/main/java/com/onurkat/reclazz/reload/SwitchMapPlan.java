/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Pure recognition of javac's guarded enum-switch table initializer, never bytecode execution. */
public record SwitchMapPlan(String holder, List<Table> tables) implements Opcodes {
    public record Entry(String constant, int branch) { }
    public record Table(String field, String enumName, List<Entry> entries) { }
    public record Parsed(SwitchMapPlan plan, String refusal) { }

    public static boolean candidate(byte[] bytes) {
        ClassNode node = node(bytes);
        return node.fields.stream().anyMatch(f -> f.name.startsWith("$SwitchMap$"));
    }
    public static Set<String> references(byte[] bytes) {
        Set<String> result = new LinkedHashSet<>();
        for (MethodNode method : node(bytes).methods) for (var insn : method.instructions)
            if (insn instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC
                    && field.name.startsWith("$SwitchMap$") && field.desc.equals("[I")
                    && field.owner.matches(".*\\$[0-9]+")) result.add(field.owner);
        return Set.copyOf(result);
    }
    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    public static Parsed parse(byte[] bytes) {
        try { return new Parsed(read(node(bytes)), null); }
        catch (RuntimeException bad) { return new Parsed(null, bad.getMessage()); }
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    private static SwitchMapPlan read(ClassNode node) {
        require(node.name.matches(".*\\$[0-9]+") && (node.access & ACC_SYNTHETIC) != 0,
                "not a synthetic javac holder");
        require("java/lang/Object".equals(node.superName) && node.interfaces.isEmpty(), "unexpected holder hierarchy");
        Set<String> fields = new LinkedHashSet<>();
        for (FieldNode field : node.fields) {
            require(field.name.startsWith("$SwitchMap$") && field.desc.equals("[I")
                    && field.access == (ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC) && field.value == null,
                    "non-switch-map field");
            require(fields.add(field.name), "duplicate table field");
        }
        require(!fields.isEmpty() && node.methods.size() == 1, "extra or missing holder method");
        MethodNode method = node.methods.get(0);
        require(method.name.equals("<clinit>") && method.desc.equals("()V"), "not a table initializer");
        List<AbstractInsnNode> code = new ArrayList<>();
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        for (var insn : method.instructions) {
            if (insn instanceof LabelNode label) labels.put(label, code.size());
            else if (insn.getOpcode() >= 0) code.add(insn);
        }
        List<Table> tables = new ArrayList<>();
        Set<TryCatchBlockNode> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        int i = 0;
        while (i < code.size() && code.get(i).getOpcode() != RETURN) {
            require(i + 4 <= code.size() && code.get(i) instanceof MethodInsnNode, "missing enum allocation");
            MethodInsnNode values = (MethodInsnNode) code.get(i);
            require(values.getOpcode() == INVOKESTATIC && !values.itf && values.name.equals("values")
                    && values.desc.equals("()[L" + values.owner + ";"), "unexpected values call");
            require(code.get(i + 1).getOpcode() == ARRAYLENGTH
                    && code.get(i + 2) instanceof IntInsnNode array && array.getOpcode() == NEWARRAY
                    && array.operand == T_INT, "unexpected table allocation");
            require(code.get(i + 3) instanceof FieldInsnNode, "missing table assignment");
            FieldInsnNode target = (FieldInsnNode) code.get(i + 3);
            require(target.getOpcode() == PUTSTATIC && target.owner.equals(node.name) && target.desc.equals("[I")
                    && target.name.equals("$SwitchMap$" + values.owner.replace('/', '$'))
                    && fields.remove(target.name), "unexpected table field assignment");
            i += 4;
            List<Entry> entries = new ArrayList<>(); Set<String> names = new HashSet<>(); Set<Integer> branches = new HashSet<>();
            while (i < code.size() && code.get(i).getOpcode() == GETSTATIC) {
                require(i + 7 <= code.size(), "incomplete guarded table entry");
                FieldInsnNode map = (FieldInsnNode) code.get(i);
                require(map.owner.equals(node.name) && map.name.equals(target.name) && map.desc.equals("[I"), "wrong entry table");
                require(code.get(i + 1) instanceof FieldInsnNode, "missing enum constant");
                FieldInsnNode constant = (FieldInsnNode) code.get(i + 1);
                require(constant.getOpcode() == GETSTATIC && constant.owner.equals(values.owner)
                        && constant.desc.equals("L" + values.owner + ";"), "wrong enum constant");
                require(code.get(i + 2) instanceof MethodInsnNode, "missing ordinal");
                MethodInsnNode ordinal = (MethodInsnNode) code.get(i + 2);
                require(ordinal.getOpcode() == INVOKEVIRTUAL && !ordinal.itf && ordinal.owner.equals(values.owner)
                        && ordinal.name.equals("ordinal") && ordinal.desc.equals("()I"), "unexpected ordinal call");
                int branch = integer(code.get(i + 3));
                require(branch == entries.size() + 1 && names.add(constant.name) && branches.add(branch), "duplicate or invalid case");
                require(code.get(i + 4).getOpcode() == IASTORE && code.get(i + 5) instanceof JumpInsnNode
                        && code.get(i + 6) instanceof VarInsnNode, "unexpected guarded store");
                JumpInsnNode jump = (JumpInsnNode) code.get(i + 5);
                VarInsnNode handler = (VarInsnNode) code.get(i + 6);
                require(jump.getOpcode() == GOTO && Objects.equals(labels.get(jump.label), i + 7)
                        && handler.getOpcode() == ASTORE && handler.var == 0, "unexpected exception control flow");
                int start = i;
                var guards = method.tryCatchBlocks.stream().filter(t -> "java/lang/NoSuchFieldError".equals(t.type)
                        && Objects.equals(labels.get(t.start), start) && Objects.equals(labels.get(t.end), start + 5)
                        && Objects.equals(labels.get(t.handler), start + 6)).toList();
                require(guards.size() == 1 && consumed.add(guards.get(0)), "missing or ambiguous NoSuchFieldError guard");
                entries.add(new Entry(constant.name, branch)); i += 7;
            }
            tables.add(new Table(target.name, values.owner, List.copyOf(entries)));
        }
        require(i == code.size() - 1 && code.get(i).getOpcode() == RETURN && fields.isEmpty()
                && consumed.size() == method.tryCatchBlocks.size(), "extra initializer instructions or guards");
        return new SwitchMapPlan(node.name, List.copyOf(tables));
    }
    private static int integer(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
        if (insn instanceof IntInsnNode value && (op == BIPUSH || op == SIPUSH)) return value.operand;
        if (insn instanceof LdcInsnNode value && value.cst instanceof Integer number) return number;
        throw new IllegalArgumentException("case is not an integer literal");
    }
}
