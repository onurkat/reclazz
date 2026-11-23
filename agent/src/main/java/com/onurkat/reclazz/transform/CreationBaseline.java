/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * What decided how a Spring {@code @Configuration} singleton was created, as first defined.
 *
 * <p>An added {@code @Bean} on a raw configuration reuses the singleton Spring created at startup, so it is only correct
 * while nothing that shaped that creation has changed: instance fields and their annotations, constructors, methods with
 * lifecycle or injection annotations, and every method of the class those call. The digest of exactly that is recorded at
 * first load and compared with each later save. Only classes annotated {@code @Configuration} are recorded; loaders are held
 * weakly.
 */
public final class CreationBaseline {
    private CreationBaseline() { }

    private static final byte[] CONFIGURATION = "Lorg/springframework/context/annotation/Configuration;".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> CREATION_ANNOTATIONS = Set.of(
            "Ljakarta/annotation/PostConstruct;", "Ljakarta/annotation/PreDestroy;",
            "Ljavax/annotation/PostConstruct;", "Ljavax/annotation/PreDestroy;",
            "Lorg/springframework/beans/factory/annotation/Autowired;", "Lorg/springframework/beans/factory/annotation/Value;",
            "Ljakarta/inject/Inject;", "Ljavax/inject/Inject;", "Ljakarta/annotation/Resource;", "Ljavax/annotation/Resource;");

    private static final class LoaderKey extends WeakReference<ClassLoader> {
        final int hash;
        LoaderKey(ClassLoader loader, ReferenceQueue<ClassLoader> queue) { super(loader, queue); hash = System.identityHashCode(loader); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) { return this == other || other instanceof LoaderKey k && get() != null && get() == k.get(); }
    }
    private static final ReferenceQueue<ClassLoader> COLLECTED = new ReferenceQueue<>();
    private static final Map<LoaderKey, Map<String, String>> DIGESTS = new HashMap<>();

    /** At first definition only. A cheap constant-pool scan decides whether the class is a configuration at all. */
    public static void defined(ClassLoader loader, String internalName, byte[] raw) {
        if (loader == null || !contains(raw, CONFIGURATION)) return;
        String digest;
        try { digest = digest(raw); } catch (RuntimeException unreadable) { return; }
        synchronized (DIGESTS) {
            purge();
            DIGESTS.computeIfAbsent(new LoaderKey(loader, COLLECTED), ignored -> new HashMap<>()).putIfAbsent(internalName, digest);
        }
    }

    /** The startup digest, or null when none was recorded (not a configuration at first load, or loaded before the agent). */
    public static String startup(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (loader == null) return null;
        synchronized (DIGESTS) {
            purge();
            Map<String, String> owners = DIGESTS.get(new LoaderKey(loader, null));
            return owners == null ? null : owners.get(type.getName().replace('.', '/'));
        }
    }

    /** Digest of the creation-relevant parts of a class file. */
    public static String digest(byte[] raw) {
        ClassNode cls = new ClassNode();
        new ClassReader(raw).accept(cls, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        StringBuilder out = new StringBuilder();
        List<FieldNode> fields = new ArrayList<>(cls.fields);
        fields.sort(Comparator.comparing((FieldNode f) -> f.name).thenComparing(f -> f.desc));
        for (FieldNode field : fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0 || com.onurkat.reclazz.bootstrap.InjectedNames.isInjected(field.name)) continue;
            out.append("F:").append(field.access).append(':').append(field.name).append(field.desc).append(':').append(field.signature)
                    .append(annotations(field.visibleAnnotations)).append(annotations(field.invisibleAnnotations)).append(';');
        }
        Map<String, MethodNode> methods = new HashMap<>();
        for (MethodNode method : cls.methods) methods.put(method.name + method.desc, method);
        Deque<MethodNode> pending = new ArrayDeque<>();
        for (MethodNode method : cls.methods)
            if (method.name.equals("<init>") || creationAnnotated(method)) pending.add(method);
        Set<String> seen = new TreeSet<>();
        Map<String, String> bodies = new TreeMap<>();
        while (!pending.isEmpty()) {
            MethodNode method = pending.poll();
            if (!seen.add(method.name + method.desc)) continue;
            StringBuilder body = new StringBuilder().append(method.access).append(annotations(method.visibleAnnotations))
                    .append(parameterAnnotations(method.visibleParameterAnnotations)).append('|');
            Map<LabelNode, Integer> labels = new IdentityHashMap<>();
            int position = 0;
            for (AbstractInsnNode insn : method.instructions) { if (insn instanceof LabelNode l) labels.put(l, position); if (insn.getOpcode() >= 0) position++; }
            for (AbstractInsnNode insn : method.instructions) {
                int op = insn.getOpcode(); if (op < 0) continue;
                body.append(op).append(':');
                if (insn instanceof MethodInsnNode call) {
                    body.append(call.owner).append('.').append(call.name).append(call.desc);
                    MethodNode own = call.owner.equals(cls.name) ? methods.get(call.name + call.desc) : null;
                    if (own != null) pending.add(own);
                } else if (insn instanceof FieldInsnNode f) body.append(f.owner).append('.').append(f.name).append(f.desc);
                else if (insn instanceof LdcInsnNode l) body.append(l.cst.getClass().getName()).append(':').append(l.cst);
                else if (insn instanceof TypeInsnNode t) body.append(t.desc);
                else if (insn instanceof IntInsnNode i) body.append(i.operand);
                else if (insn instanceof VarInsnNode v) body.append(v.var);
                else if (insn instanceof IincInsnNode i) body.append(i.var).append(':').append(i.incr);
                else if (insn instanceof JumpInsnNode j) body.append(labels.get(j.label));
                else if (insn instanceof InvokeDynamicInsnNode d) {
                    body.append(d.name).append(d.desc).append(d.bsm).append(Arrays.toString(d.bsmArgs));
                    // A lambda or method reference built during creation runs a same-class body too (audit F1a).
                    for (Object argument : d.bsmArgs)
                        if (argument instanceof org.objectweb.asm.Handle handle && handle.getOwner().equals(cls.name)) {
                            MethodNode target = methods.get(handle.getName() + handle.getDesc());
                            if (target != null) pending.add(target);
                        }
                }
                else if (insn instanceof TableSwitchInsnNode t) body.append(t.min).append(':').append(t.max).append(':').append(labels.get(t.dflt)).append(t.labels.stream().map(labels::get).toList());
                else if (insn instanceof LookupSwitchInsnNode t) body.append(t.keys).append(':').append(labels.get(t.dflt)).append(t.labels.stream().map(labels::get).toList());
                else if (insn instanceof MultiANewArrayInsnNode m) body.append(m.desc).append(':').append(m.dims);
                body.append(';');
            }
            for (TryCatchBlockNode handler : method.tryCatchBlocks)
                body.append("H:").append(labels.get(handler.start)).append(':').append(labels.get(handler.end)).append(':')
                        .append(labels.get(handler.handler)).append(':').append(handler.type).append(';');
            bodies.put(method.name + method.desc, body.toString());
        }
        bodies.forEach((key, body) -> out.append("M:").append(key).append('=').append(body).append(';'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(out.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean creationAnnotated(MethodNode method) {
        if (method.visibleAnnotations != null) for (AnnotationNode a : method.visibleAnnotations) if (CREATION_ANNOTATIONS.contains(a.desc)) return true;
        return false;
    }
    /** By content, never by AnnotationNode identity, so the same class file always gives the same digest (audit F1b). */
    private static String parameterAnnotations(List<AnnotationNode>[] parameters) {
        if (parameters == null) return "P[]";
        StringBuilder out = new StringBuilder("P[");
        for (List<AnnotationNode> parameter : parameters) out.append(annotations(parameter)).append(',');
        return out.append(']').toString();
    }
    private static String annotations(List<AnnotationNode> annotations) {
        if (annotations == null) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (AnnotationNode a : annotations) out.append(token(a.desc + value(a.values)));
        return out.append(']').toString();
    }
    /** Unambiguous: every item is length-prefixed, so different arrays never render alike (audit round 3 F1b). */
    private static String value(Object value) {
        if (value instanceof AnnotationNode nested) return token("@" + nested.desc + value(nested.values));
        if (value instanceof List<?> list) { StringBuilder out = new StringBuilder("L").append(list.size()).append('['); for (Object item : list) out.append(value(item)); return token(out.append(']').toString()); }
        if (value instanceof Object[] array) return value(Arrays.asList(array));
        return token((value == null ? "null" : value.getClass().getName()) + "=" + value);
    }
    private static String token(String text) { return text.length() + ":" + text; }
    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
    private static void purge() {
        Reference<? extends ClassLoader> dead;
        while ((dead = COLLECTED.poll()) != null) DIGESTS.remove(dead);
    }
}
