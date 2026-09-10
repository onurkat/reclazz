/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.*;

/** Saved annotations for Spring's real operation sources; methods never execute here. */
final class AddedOperationMetadata {
    private static final String TX = "Lorg/springframework/transaction/annotation/Transactional;";
    private static final Set<String> CACHE = Set.of("Lorg/springframework/cache/annotation/Cacheable;",
            "Lorg/springframework/cache/annotation/CachePut;", "Lorg/springframework/cache/annotation/CacheEvict;",
            "Lorg/springframework/cache/annotation/Caching;");
    private static final Set<String> CLASS_METADATA = Set.of("Lorg/springframework/stereotype/Service;",
            "Lorg/springframework/stereotype/Component;", "Lorg/springframework/stereotype/Repository;",
            "Lorg/springframework/cache/annotation/CacheConfig;", "Ljava/lang/Deprecated;");
    // Owned by other added-method adapters. A method carrying one of these is
    // not an operation refusal here: it may still take transaction/cache advice
    // and its scheduling/event registration is another adapter's job.
    private static final Set<String> OTHER_ADAPTERS = Set.of(
            "Lorg/springframework/scheduling/annotation/Scheduled;",
            "Lorg/springframework/scheduling/annotation/Schedules;",
            "Lorg/springframework/context/event/EventListener;",
            "Lorg/springframework/transaction/event/TransactionalEventListener;");
    record Entry(Method method, String key, String reason, boolean transaction, boolean cache) { }
    record Plan(List<Entry> entries, boolean operations) { }

    static Plan create(Class<?> owner, byte[] bytes, MethodHandles.Lookup lookup, boolean previouslyActive) throws IllegalAccessException {
        var source = new ClassNode();
        new ClassReader(bytes).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        boolean classTx = has(source.visibleAnnotations, Set.of(TX));
        boolean classCache = has(source.visibleAnnotations, CACHE);
        boolean operations = classTx || classCache || source.methods.stream().anyMatch(m ->
                has(m.visibleAnnotations, Set.of(TX)) || has(m.visibleAnnotations, CACHE));
        if (!operations && !previouslyActive) return new Plan(List.of(), false);
        Set<String> reflected = new HashSet<>();
        for (Method method : owner.getDeclaredMethods()) reflected.add(method.getName() + Type.getMethodDescriptor(method));
        List<MethodNode> added = source.methods.stream().filter(m -> !m.name.startsWith("<")
                && !reflected.contains(m.name + m.desc) && (m.access & Opcodes.ACC_PUBLIC) != 0
                && (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0)
                .filter(m -> !alreadyResolvable(owner, lookup, m)).toList();
        if (added.isEmpty()) return new Plan(List.of(), operations);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                source.name + "$__reclazz$Operations", null, "java/lang/Object", null);
        if (source.visibleAnnotations != null) for (var annotation : source.visibleAnnotations)
            annotation.accept(writer.visitAnnotation(annotation.desc, true));
        Map<String, String> reasons = new HashMap<>();
        for (MethodNode method : added) {
            String reason = null;
            if (source.signature != null || method.signature != null) reason = "generic operation metadata";
            if ((source.access & Opcodes.ACC_FINAL) != 0 || (method.access & Opcodes.ACC_FINAL) != 0) reason = "final class or method";
            if (source.visibleAnnotations != null) for (var a : source.visibleAnnotations)
                if (!a.desc.equals(TX) && !CACHE.contains(a.desc) && !CLASS_METADATA.contains(a.desc))
                    reason = "unsupported class annotation " + a.desc;
            if (method.visibleAnnotations != null) for (var a : method.visibleAnnotations)
                if (!a.desc.equals(TX) && !CACHE.contains(a.desc) && !a.desc.equals("Ljava/lang/Deprecated;")
                        && !OTHER_ADAPTERS.contains(a.desc))
                    reason = "unsupported method annotation " + a.desc;
            if (reason != null) reasons.put(method.name + method.desc, reason);
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method.name, method.desc, null,
                    method.exceptions.toArray(String[]::new));
            if (method.parameters != null) for (var p : method.parameters) mv.visitParameter(p.name, p.access);
            if (method.visibleAnnotations != null) for (var a : method.visibleAnnotations) a.accept(mv.visitAnnotation(a.desc, true));
            if (method.visibleParameterAnnotations != null) for (int i = 0; i < method.visibleParameterAnnotations.length; i++) {
                var annotations = method.visibleParameterAnnotations[i];
                if (annotations != null) for (var a : annotations) a.accept(mv.visitParameterAnnotation(i, a.desc, true));
            }
            mv.visitCode(); mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ATHROW); mv.visitMaxs(0, 0); mv.visitEnd();
        }
        writer.visitEnd();
        Class<?> schema = lookup.defineHiddenClass(writer.toByteArray(), false, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        var entries = new ArrayList<Entry>();
        for (Method method : schema.getDeclaredMethods()) {
            String descriptor = Type.getMethodDescriptor(method);
            MethodNode node = added.stream().filter(m -> m.name.equals(method.getName()) && m.desc.equals(descriptor)).findFirst().orElseThrow();
            String reason = reasons.get(node.name + node.desc);
            if (java.util.concurrent.Future.class.isAssignableFrom(method.getReturnType())
                    || java.util.concurrent.CompletionStage.class.isAssignableFrom(method.getReturnType())
                    || publisher(method.getReturnType())) reason = "asynchronous operation result";
            entries.add(new Entry(method, InjectedNames.siteKey(method.getName(), InjectedNames.descHash(descriptor)), reason,
                    classTx || has(node.visibleAnnotations, Set.of(TX)), classCache || has(node.visibleAnnotations, CACHE)));
        }
        return new Plan(entries, operations);
    }
    private static boolean has(List<AnnotationNode> annotations, Set<String> names) {
        return annotations != null && annotations.stream().anyMatch(a -> names.contains(a.desc));
    }
    private static boolean alreadyResolvable(Class<?> owner, MethodHandles.Lookup lookup, MethodNode method) {
        try {
            // An inherited method follows the bootstrap's existing-method path;
            // its newly added override must not be advertised as covered here.
            lookup.findVirtual(owner, method.name,
                    java.lang.invoke.MethodType.fromMethodDescriptorString(method.desc, owner.getClassLoader()));
            return true;
        } catch (NoSuchMethodException missing) { return false; }
        catch (IllegalAccessException unreadable) { return true; }
    }
    private static boolean publisher(Class<?> type) {
        if (type == null) return false;
        if (type.getName().equals("org.reactivestreams.Publisher")
                || type == java.util.concurrent.Flow.Publisher.class) return true;
        for (Class<?> parent : type.getInterfaces()) if (publisher(parent)) return true;
        return publisher(type.getSuperclass());
    }
}
