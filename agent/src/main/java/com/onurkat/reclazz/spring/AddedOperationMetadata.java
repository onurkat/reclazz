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
    static final String ASYNC = "Lorg/springframework/scheduling/annotation/Async;";
    private static final String EVENT = "Lorg/springframework/context/event/EventListener;";
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
    record Entry(Method method, String key, String reason, boolean transaction, boolean cache, boolean async, boolean security) { }
    record Plan(List<Entry> entries, boolean operations) { }

    static Plan create(Class<?> owner, byte[] bytes, MethodHandles.Lookup lookup, boolean previouslyActive) throws IllegalAccessException {
        var source = new ClassNode();
        new ClassReader(bytes).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        var transactions = new ComposedTransactionAnnotations(owner.getClassLoader());
        boolean classTx = transactions.has(source.visibleAnnotations);
        boolean composedClassTx = transactions.composed(source.visibleAnnotations);
        var caches = new ComposedCacheAnnotations(owner.getClassLoader());
        boolean classCache = caches.has(source.visibleAnnotations);
        boolean composedClassCache = caches.composed(source.visibleAnnotations);
        var securityAnnotations = new ComposedSecurityAnnotations(owner.getClassLoader());
        boolean classSecurity = SpringSecurityAdvice.hasSecurity(source.visibleAnnotations, owner.getClassLoader());
        boolean operations = classTx || classCache || classSecurity || source.methods.stream().anyMatch(m ->
                transactions.has(m.visibleAnnotations) || SpringSecurityAdvice.hasSecurity(m.visibleAnnotations, owner.getClassLoader()) || has(m.visibleAnnotations, Set.of(ASYNC)) || caches.has(m.visibleAnnotations));
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
            boolean methodTx = transactions.has(method.visibleAnnotations);
            boolean methodCache = caches.has(method.visibleAnnotations);
            if (source.signature != null || (method.signature != null && !signatureMetadata(method, classTx || methodTx, classCache || methodCache)))
                reason = "generic operation metadata";
            if ((source.access & Opcodes.ACC_FINAL) != 0 || (method.access & Opcodes.ACC_FINAL) != 0) reason = "final class or method";
            if (source.visibleAnnotations != null) for (var a : source.visibleAnnotations)
                if (!transactions.supported(a.desc) && !caches.supported(a.desc) && !securityAnnotations.supported(a.desc) && !CLASS_METADATA.contains(a.desc))
                    reason = "unsupported class annotation " + a.desc;
            if (method.visibleAnnotations != null) for (var a : method.visibleAnnotations)
                if (!transactions.supported(a.desc) && !a.desc.equals(ASYNC) && !securityAnnotations.supported(a.desc) && !caches.supported(a.desc) && !a.desc.equals("Ljava/lang/Deprecated;")
                        && !OTHER_ADAPTERS.contains(a.desc)
                        && !(a.desc.equals("Lorg/springframework/core/annotation/Order;")
                        && has(method.visibleAnnotations, Set.of("Lorg/springframework/context/event/EventListener;"))))
                    reason = "unsupported method annotation " + a.desc;
            if ((composedClassTx || transactions.composed(method.visibleAnnotations))
                    && has(method.visibleAnnotations, OTHER_ADAPTERS))
                reason = "composed transaction annotations require an ordinary service method";
            if ((composedClassCache || caches.composed(method.visibleAnnotations))
                    && (has(method.visibleAnnotations, OTHER_ADAPTERS) || has(method.visibleAnnotations, Set.of(ASYNC))))
                reason = "composed cache annotations require an ordinary synchronous service method";
            if (has(method.visibleAnnotations, Set.of(ASYNC))) {
                if (!asyncService(method) && !asyncCallback(method))
                    reason = "unsupported method annotation " + ASYNC + ": requires a direct void EventListener or no-argument void Scheduled method";
                if (asyncService(method) && !asyncReturn(method.desc))
                    reason = "unsupported method annotation " + ASYNC + ": service return must be void, Future or CompletableFuture";
                String callback = callbackProblem(source, method, asyncService(method));
                if (callback != null) reason = callback;
            }
            boolean security = classSecurity || SpringSecurityAdvice.hasSecurity(method.visibleAnnotations, owner.getClassLoader());
            if (securityAnnotations.duplicatePolicies(source.visibleAnnotations)
                    || securityAnnotations.duplicatePolicies(method.visibleAnnotations))
                reason = "multiple security annotations declare the same pre/post policy";
            if (security && (has(method.visibleAnnotations, OTHER_ADAPTERS) || has(method.visibleAnnotations, Set.of(ASYNC))))
                reason = "security requires an ordinary synchronous service method";
            if (security && (!"java/lang/Object".equals(source.superName) || !source.interfaces.isEmpty()))
                reason = "security requires a direct Object subclass without interfaces";
            if (reason != null) reasons.put(method.name + method.desc, reason);
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method.name, method.desc,
                    signatureMetadata(method, classTx || methodTx, classCache || methodCache) ? method.signature : null,
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
            boolean signature = signatureMetadata(node, classTx || transactions.has(node.visibleAnnotations), classCache || caches.has(node.visibleAnnotations));
            if (signature && !concreteSignature(method)) reason = "generic async operation metadata requires concrete types";
            boolean security = classSecurity || SpringSecurityAdvice.hasSecurity(node.visibleAnnotations, owner.getClassLoader());
            if ((!signature || security) && (java.util.concurrent.Future.class.isAssignableFrom(method.getReturnType())
                    || java.util.concurrent.CompletionStage.class.isAssignableFrom(method.getReturnType())
                    || publisher(method.getReturnType()))) reason = "asynchronous operation result";
            entries.add(new Entry(method, InjectedNames.siteKey(method.getName(), InjectedNames.descHash(descriptor)), reason,
                    classTx || transactions.has(node.visibleAnnotations), classCache || caches.has(node.visibleAnnotations), has(node.visibleAnnotations, Set.of(ASYNC)),
                    security));
        }
        return new Plan(entries, operations);
    }
    static boolean isOperationAnnotation(String descriptor) {
        return TX.equals(descriptor) || CACHE.contains(descriptor);
    }

    // Callback delegates use bootstrapMethod, whose operation metadata only
    // covers newly added public methods. Refuse shapes that could miss that route.
    static String callbackProblem(ClassNode owner, MethodNode method) {
        return callbackProblem(owner, method, false);
    }

    private static String callbackProblem(ClassNode owner, MethodNode method, boolean concreteGenerics) {
        if (method.visibleAnnotations == null || method.visibleAnnotations.stream()
                .noneMatch(a -> isOperationAnnotation(a.desc) || ASYNC.equals(a.desc))) return null;
        if ((method.access & Opcodes.ACC_PUBLIC) == 0 || (method.access & (Opcodes.ACC_FINAL
                | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE
                | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                || (owner.access & Opcodes.ACC_FINAL) != 0)
            return "operation callbacks require public non-final concrete instance methods on a non-final class";
        if (owner.signature != null || (method.signature != null && !concreteGenerics))
            return "generic operation callbacks are unsupported";
        if (!"java/lang/Object".equals(owner.superName) || !owner.interfaces.isEmpty())
            return "operation callbacks require a direct Object subclass without interfaces";
        return null;
    }

    private static boolean asyncService(MethodNode method) {
        return has(method.visibleAnnotations, Set.of(ASYNC)) && !has(method.visibleAnnotations, OTHER_ADAPTERS);
    }

    private static boolean asyncCallback(MethodNode method) {
        if (has(method.visibleAnnotations, Set.of("Lorg/springframework/transaction/event/TransactionalEventListener;"))) return false;
        boolean event = has(method.visibleAnnotations, Set.of(EVENT));
        boolean scheduled = has(method.visibleAnnotations, Set.of(AddedScheduledAdapter.SCHEDULED, AddedScheduledAdapter.SCHEDULES));
        return scheduled ? !event && method.desc.equals("()V")
                : event && Type.getReturnType(method.desc).getSort() == Type.VOID;
    }

    private static boolean asyncReturn(String descriptor) {
        String result = Type.getReturnType(descriptor).getDescriptor();
        return result.equals("V") || result.equals("Ljava/util/concurrent/Future;")
                || result.equals("Ljava/util/concurrent/CompletableFuture;");
    }

    private static boolean signatureMetadata(MethodNode method, boolean classTx, boolean classCache) {
        if (asyncService(method)) return asyncReturn(method.desc);
        // Removing Async restores an ordinary synchronous Future-returning method.
        // It must not inherit a refusal merely because this owner once had advice.
        return !classTx && !classCache && !has(method.visibleAnnotations, Set.of(TX, ASYNC))
                && !has(method.visibleAnnotations, CACHE) && !has(method.visibleAnnotations, OTHER_ADAPTERS)
                && Type.getReturnType(method.desc).getSort() != Type.VOID && asyncReturn(method.desc);
    }

    private static boolean concreteSignature(Method method) {
        try {
            if (method.getTypeParameters().length != 0 || !concrete(method.getGenericReturnType())) return false;
            for (var parameter : method.getGenericParameterTypes()) if (!concrete(parameter)) return false;
            for (var exception : method.getGenericExceptionTypes()) if (!concrete(exception)) return false;
            return true;
        } catch (RuntimeException | LinkageError invalid) { return false; }
    }

    private static boolean concrete(java.lang.reflect.Type type) {
        if (type instanceof Class<?>) return true;
        if (type instanceof java.lang.reflect.GenericArrayType array) return concrete(array.getGenericComponentType());
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            if (!concrete(parameterized.getRawType())) return false;
            if (parameterized.getOwnerType() != null && !concrete(parameterized.getOwnerType())) return false;
            for (var argument : parameterized.getActualTypeArguments()) if (!concrete(argument)) return false;
            return true;
        }
        return false; // Type variables and wildcards are outside the saved metadata contract.
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
