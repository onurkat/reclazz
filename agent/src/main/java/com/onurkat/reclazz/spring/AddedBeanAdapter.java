/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.transform.CallSiteAdapter;
import com.onurkat.reclazz.transform.SafeClassWriter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.Supplier;

/** A factory delegate for a method that reflection cannot see on a stock JDK. */
public final class AddedBeanAdapter {
    static final String BEAN = "Lorg/springframework/context/annotation/Bean;";
    static final String CONFIGURATION = "Lorg/springframework/context/annotation/Configuration;";
    private static final String DEPRECATED = "Ljava/lang/Deprecated;";
    private AddedBeanAdapter() { }

    record Factory(MethodNode method, String name, String init, String destroy) { }
    record Plan(List<Factory> factories, List<String> refused) { }

    static Plan inspect(byte[] bytes, Set<String> added) {
        List<Factory> factories = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (bytes == null) return new Plan(factories, refused);
        ClassNode source = new ClassNode();
        new ClassReader(bytes).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        AnnotationNode config = annotation(source.visibleAnnotations, CONFIGURATION);
        boolean supportedClass = config != null && Boolean.FALSE.equals(value(config, "proxyBeanMethods", true))
                && "java/lang/Object".equals(source.superName) && source.interfaces.isEmpty()
                && !extraAnnotations(source.visibleAnnotations, CONFIGURATION);
        for (MethodNode method : source.methods) {
            if (!added.contains(method.name + ":" + method.desc)) continue;
            AnnotationNode bean = annotation(method.visibleAnnotations, BEAN);
            if (bean == null) continue;
            try {
                if (!supportedClass) throw new IllegalArgumentException(
                        "requires direct @Configuration(proxyBeanMethods=false), without inheritance or additional class annotations");
                Type signature = Type.getMethodType(method.desc);
                if (signature.getArgumentTypes().length != 0 || signature.getReturnType().getSort() != Type.OBJECT
                        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0
                        || method.signature != null)
                    throw new IllegalArgumentException("only no-argument, non-generic, object-returning instance factories are supported");
                if (extraAnnotations(method.visibleAnnotations, BEAN))
                    throw new IllegalArgumentException("additional method annotations cannot be applied to the factory delegate");
                if (bean.values != null) for (int i = 0; i < bean.values.size(); i += 2)
                    if (!Set.of("name", "value", "initMethod", "destroyMethod").contains(bean.values.get(i)))
                        throw new IllegalArgumentException("unsupported @Bean option " + bean.values.get(i));
                List<?> names = (List<?>) value(bean, "name", List.of());
                List<?> aliases = (List<?>) value(bean, "value", List.of());
                if (!names.isEmpty() && !aliases.isEmpty() && !names.equals(aliases))
                    throw new IllegalArgumentException("conflicting @Bean name/value aliases");
                if (names.isEmpty()) names = aliases;
                if (names.size() > 1) throw new IllegalArgumentException("multiple bean names/aliases are not supported");
                String name = names.isEmpty() ? method.name : (String) names.get(0);
                if (name.isBlank() || name.startsWith("&")) throw new IllegalArgumentException("unsupported bean name");
                factories.add(new Factory(method, name, (String) value(bean, "initMethod", ""),
                        (String) value(bean, "destroyMethod", "(inferred)")));
            } catch (IllegalArgumentException failure) {
                refused.add(method.name + method.desc + ": " + failure.getMessage());
            }
        }
        Map<String, Long> counts = new HashMap<>();
        for (Factory factory : factories) counts.merge(factory.name(), 1L, Long::sum);
        factories.removeIf(factory -> {
            if (counts.get(factory.name()) == 1) return false;
            refused.add(factory.method().name + factory.method().desc + ": duplicate added bean name '" + factory.name() + "'");
            return true;
        });
        return new Plan(factories, refused);
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations == null ? null : annotations.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static boolean extraAnnotations(List<AnnotationNode> annotations, String allowed) {
        return annotations != null && annotations.stream().anyMatch(a -> !a.desc.equals(allowed) && !a.desc.equals(DEPRECATED));
    }

    private static Object value(AnnotationNode annotation, String key, Object fallback) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return fallback;
    }

    // The captured lookup is a capability; this entry point stays package-private.
    static Supplier<?> create(Class<?> owner, Supplier<?> currentConfig, Factory factory) throws Throwable {
        MethodHandles.Lookup lookup = LookupCapture.get(owner);
        if (lookup == null) throw new IllegalStateException("no captured lookup for " + owner.getName());
        String internal = Type.getInternalName(owner);
        String adapter = internal + "$$ReclazzBean";
        String supplier = "Ljava/util/function/Supplier;";
        String target = InjectedNames.PREFIX + "target";
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, adapter, null,
                "java/lang/Object", new String[]{"java/util/function/Supplier"});
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, target, supplier, null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + supplier + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, target, supplier);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, adapter, target, supplier);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, internal);
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC,
                "com/onurkat/reclazz/bootstrap/ReclazzBootstrap", "bootstrapMethod",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn(factory.method().name,
                "(" + Type.getDescriptor(owner) + ")" + Type.getReturnType(factory.method().desc).getDescriptor(),
                bootstrap, internal, CallSiteAdapter.descHash(factory.method().desc));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        MethodHandles.Lookup hidden = lookup.defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        return (Supplier<?>) hidden.findConstructor(hidden.lookupClass(), MethodType.methodType(void.class, Supplier.class))
                .invoke(currentConfig);
    }
}
