/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import java.util.*;
import java.io.IOException;
import com.onurkat.reclazz.util.ClassLookup.LoadedClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Parse the whole captured save before any companion target is published. */
public final class SwitchMapPreflight {
    private SwitchMapPreflight() { }
    public record Result(Map<String, SwitchMapPlan> plans, Map<String, String> refused) { }
    /** javac 21 can index a switch directly by the source ordinal without a synthetic table. */
    public static void refuseDirectSwitches(Map<String, byte[]> saved, LoadedClasses loaded, Map<String, String> refused) {
        Map<String, String> hosts = new HashMap<>();
        Map<String, String> unsafeNests = new HashMap<>();
        for (var entry : saved.entrySet()) {
            ClassNode consumer = new ClassNode();
            new ClassReader(entry.getValue()).accept(consumer, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            String nestHost = hostOf(consumer);
            hosts.put(entry.getKey(), nestHost);
            for (String enumName : directSwitchOwners(consumer)) {
                for (Class<?> type : loaded.definitions(enumName)) {
                    if (!type.isEnum()) continue;
                    byte[] declaration = saved.get(enumName);
                    try {
                        if (declaration == null) declaration = resource(type, enumName);
                        if (!EnumConstantChange.ordinalMismatch(type, declaration)
                                && (saved.containsKey(enumName) || (!EnumConstantChange.ordinalMismatch(type, null)
                                && !EnumConstantChange.compiledOrdinalMismatch(type)))) continue;
                    } catch (IOException | RuntimeException unknown) {
                        // Without the compilation order the case keys cannot be verified.
                    }
                    String reason = "direct ordinal switch in " + entry.getKey()
                            + " over " + enumName + " cannot use the running ordinals";
                    refused.put(entry.getKey(), reason);
                    if (loaded.definitions(entry.getKey()).isEmpty()) unsafeNests.put(nestHost, reason);
                    if (saved.containsKey(enumName)) refused.put(enumName, reason);
                }
            }
            if ((consumer.access & Opcodes.ACC_ENUM) == 0) continue;
            for (Class<?> type : loaded.definitions(entry.getKey())) {
                if (!type.isEnum()) continue;
                EnumConstantChange.compiledOrder(type, entry.getValue());
                if (!EnumConstantChange.ordinalMismatch(type, entry.getValue())) continue;
                if (consumer.version < Opcodes.V11 && (!nestHost.equals(consumer.name) || consumer.name.contains("$"))) {
                    refused.put(entry.getKey(), "cannot verify direct ordinal switches in pre-nest class files for " + entry.getKey());
                    continue;
                }
                // A split file notification can deliver just the enum while its newly compiled nest is already on disk.
                String host = consumer.nestHostClass == null ? consumer.name : consumer.nestHostClass;
                try {
                    ClassNode nest = consumer;
                    if (!host.equals(consumer.name)) {
                        nest = new ClassNode();
                        new ClassReader(resource(type, host.replace('/', '.'))).accept(nest, ClassReader.SKIP_CODE);
                    }
                    Set<String> members = new LinkedHashSet<>();
                    members.add(host);
                    if (nest.nestMembers != null) members.addAll(nest.nestMembers);
                    for (String member : members) {
                        String name = member.replace('/', '.');
                        if (saved.containsKey(name)) continue;
                        ClassNode sibling = new ClassNode();
                        new ClassReader(resource(type, name)).accept(sibling, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        if (directSwitchOwners(sibling).contains(entry.getKey())) {
                            refused.put(entry.getKey(), "direct ordinal switch in " + name + " over " + entry.getKey());
                            break;
                        }
                    }
                } catch (IOException | RuntimeException unknown) {
                    refused.put(entry.getKey(), "cannot verify direct ordinal switches in enum nest " + host);
                }
            }
        }
        hosts.forEach((name, host) -> {
            String reason = unsafeNests.get(host);
            if (reason != null) refused.putIfAbsent(name, reason);
        });
    }

    private static String hostOf(ClassNode node) {
        if (node.nestHostClass != null) return node.nestHostClass;
        if (node.version >= Opcodes.V11) return node.name;
        String name = node.outerClass == null ? node.name : node.outerClass;
        Map<String, String> enclosing = new HashMap<>();
        for (var member : node.innerClasses) if (member.outerName != null) enclosing.put(member.name, member.outerName);
        Set<String> seen = new HashSet<>();
        while (enclosing.containsKey(name) && seen.add(name)) name = enclosing.get(name);
        return name;
    }

    private static byte[] resource(Class<?> type, String name) throws IOException {
        try (var in = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            if (in == null) throw new IOException("missing class bytes");
            byte[] bytes = in.readAllBytes();
            if (!new ClassReader(bytes).getClassName().replace('/', '.').equals(name))
                throw new IOException("unexpected class name");
            return bytes;
        }
    }

    private static Set<String> directSwitchOwners(ClassNode node) {
        Set<String> owners = new LinkedHashSet<>();
        for (MethodNode method : node.methods) for (var insn : method.instructions) {
            if (!(insn instanceof LookupSwitchInsnNode || insn instanceof TableSwitchInsnNode)) continue;
            var previous = insn.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            if (previous instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.name.equals("ordinal") && call.desc.equals("()I")) owners.add(call.owner.replace('/', '.'));
        }
        return owners;
    }

    public static Result check(Map<String, byte[]> bytes) {
        Map<String, SwitchMapPlan> plans = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        Set<String> referenced = new HashSet<>();
        bytes.values().forEach(value -> SwitchMapPlan.references(value).forEach(name -> referenced.add(name.replace('/', '.'))));
        bytes.forEach((name, value) -> {
            if (SwitchMapPlan.candidate(value) || referenced.contains(name)) {
                var parsed = SwitchMapPlan.parse(value);
                if (parsed.plan() == null) refused.put(name, parsed.refusal());
                else plans.put(name, parsed.plan());
            }
        });
        bytes.forEach((name, value) -> {
            for (String holder : SwitchMapPlan.references(value)) {
                String reason = refused.get(holder.replace('/', '.'));
                if (reason != null) refused.put(name, reason);
            }
        });
        refused.keySet().forEach(plans::remove);
        return new Result(Map.copyOf(plans), Map.copyOf(refused));
    }
}
