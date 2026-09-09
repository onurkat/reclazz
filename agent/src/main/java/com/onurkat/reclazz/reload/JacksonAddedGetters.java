/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.DispatchTable;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.bootstrap.JacksonBridge;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Method;
import java.util.*;

/** Carries saved getter metadata without adding a member to the original JVM class. */
final class JacksonAddedGetters {
    private JacksonAddedGetters() { }

    static Set<String> publish(Class<?> owner, byte[] bytes,
                               MethodHandles.Lookup lookup, Map<String, MethodHandle> targets) throws IllegalAccessException {
        try {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper", false, owner.getClassLoader());
        } catch (ClassNotFoundException | LinkageError unavailable) {
            // An application without Jackson still needs the ordinary framework
            // visibility diagnostic; there is no Jackson consumer to adapt here.
            JacksonBridge.replace(owner, null, Map.of());
            return Set.of();
        }
        ClassNode source = new ClassNode();
        new ClassReader(bytes).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<String> reflected = new HashSet<>();
        for (Method method : owner.getDeclaredMethods())
            reflected.add(method.getName() + Type.getMethodDescriptor(method));
        // Diff metadata may already include a companion getter on the second
        // save; the loaded JVM class still does not. Compare against that class.
        List<MethodNode> getters = source.methods.stream()
                .filter(m -> !reflected.contains(m.name + m.desc) && candidate(m)).toList();
        if (getters.isEmpty()) {
            JacksonBridge.replace(owner, null, Map.of());
            return Set.of();
        }
        Set<String> reflectedFields = new HashSet<>();
        for (var field : owner.getDeclaredFields())
            reflectedFields.add(field.getName() + Type.getDescriptor(field.getType()));
        for (var field : source.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0 && !reflectedFields.contains(field.name + field.desc)
                    && ((field.visibleAnnotations != null && !field.visibleAnnotations.isEmpty())
                    || (field.visibleTypeAnnotations != null && !field.visibleTypeAnnotations.isEmpty()))) {
                // Getter metadata cannot stand in for field metadata: ignoring
                // an added field's JsonIgnore could expose a property. Refuse the
                // DTO's adapter instead, including its previously published shape.
                JacksonBridge.replace(owner, null, Map.of());
                throw new IllegalStateException("Added field " + field.name
                        + " has annotations the Jackson getter adapter cannot carry; put serialization annotations"
                        + " on the getter or restart. Added getters for this DTO are not exposed to Jackson.");
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
                source.name + "$__reclazz$Jackson", typeParameters(source.signature), "java/lang/Object", null);
        for (MethodNode method : getters) {
            MethodVisitor mv = writer.visitMethod(method.access, method.name, method.desc, method.signature,
                    method.exceptions.toArray(String[]::new));
            if (method.visibleAnnotations != null)
                method.visibleAnnotations.forEach(a -> a.accept(mv.visitAnnotation(a.desc, true)));
            if (method.visibleTypeAnnotations != null)
                method.visibleTypeAnnotations.forEach(a -> a.accept(mv.visitTypeAnnotation(a.typeRef, a.typePath, a.desc, true)));
            // A concrete metadata method avoids Jackson preferring an inherited concrete
            // accessor over an abstract one. It must never be called without the bridge.
            mv.visitCode(); mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(0, 0); mv.visitEnd();
        }
        writer.visitEnd();
        Class<?> schema = lookup.defineHiddenClass(writer.toByteArray(), false,
                MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        Map<Method, MethodHandle> invokers = new LinkedHashMap<>();
        Set<String> covered = new LinkedHashSet<>();
        for (Method method : schema.getDeclaredMethods()) {
            String desc = Type.getMethodDescriptor(method);
            String key = InjectedNames.siteKey(method.getName(), CallSiteAdapter.descHash(desc));
            MethodHandle target = targets.get(key);
            if (target == null) throw new IllegalStateException("No companion getter target: " + method.getName());
            // Retained ObjectWriters still call the newest body, just like retained
            // application call sites. Discovery/removal uses the next mapper cache rebuild.
            MethodHandle invoker = DispatchTable.getOrCreate(owner)
                    .getOrCreateMethodSite(key, new MutableCallSite(target)).dynamicInvoker();
            invokers.put(method, invoker);
            covered.add(method.getName() + desc);
        }
        JacksonBridge.replace(owner, schema, invokers);
        return Set.copyOf(covered);
    }

    private static boolean candidate(MethodNode method) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE
                | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0 || method.name.startsWith("<")) return false;
        Type type = Type.getMethodType(method.desc);
        if (type.getArgumentTypes().length != 0 || type.getReturnType().getSort() == Type.VOID) return false;
        if ((method.name.startsWith("get") && method.name.length() > 3)
                || (method.name.startsWith("is") && method.name.length() > 2
                && type.getReturnType().getSort() == Type.BOOLEAN)) return true;
        return method.visibleAnnotations != null && method.visibleAnnotations.stream().anyMatch(a ->
                a.desc.equals("Lcom/fasterxml/jackson/annotation/JsonProperty;")
                        || a.desc.equals("Lcom/fasterxml/jackson/annotation/JsonGetter;"));
    }

    /** Copy the type-variable declarations, but not the original superclass/interface signature. */
    private static String typeParameters(String signature) {
        if (signature == null || !signature.startsWith("<")) return null;
        int depth = 0;
        for (int i = 0; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (c == '<') depth++;
            else if (c == '>' && --depth == 0) return signature.substring(0, i + 1) + "Ljava/lang/Object;";
        }
        throw new IllegalArgumentException("Incomplete DTO type parameters");
    }
}
