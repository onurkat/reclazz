/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.hybris.codegen;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Checks the declarations visible to a compiler. The caller checks consumer resolution separately. */
final class GeneratedClassCompatibility {
    private GeneratedClassCompatibility() { }

    static boolean preservesDeclarations(byte[] previous, byte[] candidate) {
        ClassNode before = declarations(previous);
        ClassNode after = declarations(candidate);
        if (hasUnknownAttributes(before) || hasUnknownAttributes(after)) return false;

        Set<String> fields = before.fields.stream().map(f -> f.name).collect(Collectors.toSet());
        Set<String> methods = before.methods.stream().map(m -> m.name).collect(Collectors.toSet());
        // Retain every old name, including added overloads that could change source resolution.
        after.fields.removeIf(f -> !fields.contains(f.name));
        after.methods.removeIf(m -> !methods.contains(m.name));
        return Arrays.equals(encode(before), encode(after));
    }

    static Set<String> newNames(byte[] previous, byte[] candidate) {
        ClassNode next = declarations(candidate);
        Set<String> names = new java.util.HashSet<>();
        if (previous == null) {
            names.add(next.name.substring(next.name.lastIndexOf('/') + 1));
        } else {
            ClassNode old = declarations(previous);
            Set<String> fields = old.fields.stream().map(f -> f.name).collect(Collectors.toSet());
            Set<String> methods = old.methods.stream().map(m -> m.name).collect(Collectors.toSet());
            next.fields.stream().filter(f -> !fields.contains(f.name)).forEach(f -> names.add(f.name));
            next.methods.stream().filter(m -> !methods.contains(m.name)).forEach(m -> names.add(m.name));
        }
        return names;
    }

    private static ClassNode declarations(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        node.sourceFile = null;
        node.sourceDebug = null;
        node.fields.sort(Comparator.comparing((FieldNode f) -> f.name).thenComparing(f -> f.desc));
        node.methods.sort(Comparator.comparing((MethodNode m) -> m.name).thenComparing(m -> m.desc));
        return node;
    }

    private static boolean hasUnknownAttributes(ClassNode node) {
        return node.module != null || node.recordComponents != null || node.attrs != null
                || node.methods.stream().anyMatch(m -> m.attrs != null)
                || node.fields.stream().anyMatch(f -> f.attrs != null);
    }

    private static byte[] encode(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
