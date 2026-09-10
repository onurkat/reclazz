/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;

/** Saved Kafka callback metadata delegating to the current plain singleton. */
public final class AddedKafkaListenerAdapter {
    static final String KAFKA = "Lorg/springframework/kafka/annotation/KafkaListener;";
    private static final String TARGET = InjectedNames.PREFIX + "target";
    private static final Set<String> OPTIONS = Set.of("id", "topics", "groupId", "containerFactory",
            "idIsGroup", "autoStartup", "concurrency");
    private static final Set<String> CLASS_ANNOTATIONS = Set.of("Lorg/springframework/stereotype/Component;",
            "Lorg/springframework/stereotype/Service;", "Lorg/springframework/stereotype/Repository;", "Ljava/lang/Deprecated;");
    private static final Set<String> PARAMETERS = Set.of("Lorg/springframework/messaging/handler/annotation/Payload;",
            "Lorg/springframework/messaging/handler/annotation/Header;", "Lorg/springframework/messaging/handler/annotation/Headers;");
    private static final class Slot { Class<?> owner; }
    private static final ClassValue<Slot> owners = new ClassValue<>() {
        @Override protected Slot computeValue(Class<?> type) { return new Slot(); }
    };
    private AddedKafkaListenerAdapter() { }
    record Plan(List<MethodNode> methods, List<String> ids, List<String> refused) { }
    record Delegate(Object bean, List<Method> methods) { }
    static Class<?> ownerOf(Object bean) {
        if (bean == null) return null;
        Class<?> owner = owners.get(bean.getClass()).owner;
        return owner == null ? bean.getClass() : owner;
    }

    static Plan inspect(byte[] bytes, Set<String> added) {
        List<MethodNode> methods = new ArrayList<>(); List<String> ids = new ArrayList<>(); List<String> refused = new ArrayList<>();
        if (bytes == null || added.isEmpty()) return new Plan(methods, ids, refused);
        var node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        for (var m : node.methods) {
            if (!added.contains(m.name + ":" + m.desc) || m.visibleAnnotations == null) continue;
            var annotation = m.visibleAnnotations.stream().filter(a -> a.desc.equals(KAFKA)).findFirst().orElse(null);
            if (annotation == null) continue;
            try {
                if (!node.superName.equals("java/lang/Object") || !node.interfaces.isEmpty() || node.signature != null)
                    throw new IllegalArgumentException("inheritance, interfaces and class type variables are unsupported");
                if (node.visibleAnnotations != null) for (var a : node.visibleAnnotations)
                    if (!CLASS_ANNOTATIONS.contains(a.desc)) throw new IllegalArgumentException("unsupported class annotation " + a.desc);
                if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0
                        || Type.getReturnType(m.desc).getSort() != Type.VOID || Type.getArgumentTypes(m.desc).length == 0 || typeVariables(m.signature))
                    throw new IllegalArgumentException("only void instance callbacks with arguments and no type variables are supported");
                for (var a : m.visibleAnnotations) if (!a.desc.equals(KAFKA) && !a.desc.equals("Ljava/lang/Deprecated;"))
                    throw new IllegalArgumentException("unsupported listener annotation " + a.desc);
                if (m.visibleParameterAnnotations != null) for (var annotations : m.visibleParameterAnnotations)
                    if (annotations != null) for (var a : annotations) if (!PARAMETERS.contains(a.desc))
                        throw new IllegalArgumentException("unsupported parameter annotation " + a.desc);
                Map<String,Object> options = new LinkedHashMap<>();
                if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
                    options.put((String) annotation.values.get(i), annotation.values.get(i + 1));
                for (String key : options.keySet()) if (!OPTIONS.contains(key))
                    throw new IllegalArgumentException("unsupported KafkaListener option " + key);
                String id = literal(options.get("id"), "id");
                Object topics = options.get("topics");
                if (!(topics instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("topics are required");
                // A topic may be a ${property} placeholder: the real processor
                // resolves it through the application's own value resolver. A
                // #{SpEL} expression stays out of scope.
                for (Object topic : list) destination(topic, "topic");
                for (String option : List.of("groupId", "containerFactory"))
                    if (options.containsKey(option)) literal(options.get(option), option);
                if (options.containsKey("autoStartup") && !Set.of("true", "false").contains(options.get("autoStartup")))
                    throw new IllegalArgumentException("autoStartup must be literal true or false");
                if (options.containsKey("concurrency") && Integer.parseInt(literal(options.get("concurrency"), "concurrency")) <= 0)
                    throw new IllegalArgumentException("concurrency must be positive");
                if (ids.contains(id)) throw new IllegalArgumentException("duplicate added listener id " + id);
                methods.add(m); ids.add(id);
            } catch (RuntimeException invalid) { refused.add(m.name + m.desc + ": " + com.onurkat.reclazz.ui.Failures.describe(invalid)); }
        }
        return new Plan(List.copyOf(methods), List.copyOf(ids), List.copyOf(refused));
    }
    private static String literal(Object value, String name) {
        if (!(value instanceof String s) || s.isBlank() || s.contains("#{") || s.contains("${"))
            throw new IllegalArgumentException(name + " must be a nonempty literal");
        return (String) value;
    }
    // A destination may carry a ${property} placeholder but not a #{SpEL}
    // expression, which the added-listener path does not evaluate here.
    private static String destination(Object value, String name) {
        if (!(value instanceof String s) || s.isBlank() || s.contains("#{"))
            throw new IllegalArgumentException(name + " must be a nonempty literal or ${property} placeholder");
        return (String) value;
    }
    private static boolean typeVariables(String signature) {
        if (signature == null) return false;
        boolean[] found = {false};
        new org.objectweb.asm.signature.SignatureReader(signature).accept(new org.objectweb.asm.signature.SignatureVisitor(Opcodes.ASM9) {
            @Override public void visitFormalTypeParameter(String name) { found[0] = true; }
            @Override public void visitTypeVariable(String name) { found[0] = true; }
        });
        return found[0];
    }

    // Captured lookup is confined to generating inspected methods; never exposed.
    static Delegate create(Class<?> owner, Supplier<Object> current, Plan plan) throws Throwable {
        if (!plan.refused().isEmpty()) throw new IllegalArgumentException("invalid Kafka listener plan");
        var lookup = LookupCapture.get(owner);
        if (lookup == null) throw new IllegalStateException("no captured lookup for " + owner.getName());
        String internal = Type.getInternalName(owner), generated = internal + "$$ReclazzKafka";
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, generated, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, TARGET, "Ljava/util/function/Supplier;", null, null).visitEnd();
        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/function/Supplier;)V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, generated, TARGET, "Ljava/util/function/Supplier;");
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        for (var m : plan.methods()) {
            var mv = writer.visitMethod(Opcodes.ACC_PUBLIC, m.name, m.desc, m.signature, m.exceptions.toArray(String[]::new));
            for (var a : m.visibleAnnotations) a.accept(mv.visitAnnotation(a.desc, true));
            if (m.parameters != null) for (var p : m.parameters) mv.visitParameter(p.name, p.access);
            if (m.visibleParameterAnnotations != null) for (int i = 0; i < m.visibleParameterAnnotations.length; i++)
                if (m.visibleParameterAnnotations[i] != null) for (var a : m.visibleParameterAnnotations[i]) a.accept(mv.visitParameterAnnotation(i, a.desc, true));
            mv.visitCode(); mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, generated, TARGET, "Ljava/util/function/Supplier;");
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, internal);
            int slot = 1;
            for (Type arg : Type.getArgumentTypes(m.desc)) { mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot); slot += arg.getSize(); }
            Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "com/onurkat/reclazz/bootstrap/ReclazzBootstrap", "bootstrapMethod",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);
            mv.visitInvokeDynamicInsn(m.name, "(" + Type.getDescriptor(owner) + m.desc.substring(1), bootstrap, internal, CallSiteAdapter.descHash(m.desc));
            mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        }
        writer.visitEnd();
        var hidden = lookup.defineHiddenClass(writer.toByteArray(), false, MethodHandles.Lookup.ClassOption.NESTMATE);
        owners.get(hidden.lookupClass()).owner = owner;
        Object bean = hidden.findConstructor(hidden.lookupClass(), MethodType.methodType(void.class, Supplier.class)).invoke(current);
        List<Method> methods = new ArrayList<>();
        for (var m : plan.methods()) methods.add(hidden.lookupClass().getDeclaredMethod(m.name,
                MethodType.fromMethodDescriptorString(m.desc, owner.getClassLoader()).parameterArray()));
        return new Delegate(bean, List.copyOf(methods));
    }
}
