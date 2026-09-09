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
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** A factory delegate for a method that reflection cannot see on a stock JDK. */
public final class AddedBeanAdapter {
    static final String BEAN = "Lorg/springframework/context/annotation/Bean;";
    static final String CONFIGURATION = "Lorg/springframework/context/annotation/Configuration;";
    private static final String DEPRECATED = "Ljava/lang/Deprecated;";
    private static final String QUALIFIER = "Lorg/springframework/beans/factory/annotation/Qualifier;";
    private static final String PRIMARY = "Lorg/springframework/context/annotation/Primary;";
    private static final String PARAMETERS = InjectedNames.PREFIX + "parameters";
    private AddedBeanAdapter() { }

    record Factory(MethodNode method, List<String> names, String init, String destroy,
                   boolean primary, String qualifier) {
        String name() { return names.get(0); }
        List<String> aliases() { return names.subList(1, names.size()); }
    }
    record Plan(List<Factory> factories, List<String> refused) { }

    static Plan inspect(byte[] bytes, Set<String> added) {
        List<Factory> factories = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (bytes == null) return new Plan(factories, refused);
        ClassNode source = new ClassNode();
        // Preserve debug argument names just as the added event adapter does.
        new ClassReader(bytes).accept(source, ClassReader.SKIP_FRAMES);
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
                if (signature.getReturnType().getSort() != Type.OBJECT
                        || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0
                        || method.signature != null)
                    throw new IllegalArgumentException("only non-generic, object-returning instance factories are supported");
                for (Type argument : signature.getArgumentTypes())
                    if (argument.getSort() != Type.OBJECT)
                        throw new IllegalArgumentException("factory parameters must be required reference beans; primitive/array parameters are unsupported");
                if (method.visibleParameterAnnotations != null)
                    for (var annotations : method.visibleParameterAnnotations)
                        if (annotations != null && annotations.stream().anyMatch(a -> !a.desc.equals(QUALIFIER)))
                            throw new IllegalArgumentException("only direct @Qualifier is supported on factory parameters");
                if (signature.getArgumentTypes().length > 0
                        && method.visibleTypeAnnotations != null && !method.visibleTypeAnnotations.isEmpty())
                    throw new IllegalArgumentException("factory type annotations are not supported");
                if (extraAnnotations(method.visibleAnnotations, BEAN, PRIMARY, QUALIFIER))
                    throw new IllegalArgumentException("additional method annotations cannot be applied to the factory delegate");
                if (bean.values != null) for (int i = 0; i < bean.values.size(); i += 2)
                    if (!Set.of("name", "value", "initMethod", "destroyMethod").contains(bean.values.get(i)))
                        throw new IllegalArgumentException("unsupported @Bean option " + bean.values.get(i));
                List<?> names = (List<?>) value(bean, "name", List.of());
                List<?> aliases = (List<?>) value(bean, "value", List.of());
                if (!names.isEmpty() && !aliases.isEmpty() && !names.equals(aliases))
                    throw new IllegalArgumentException("conflicting @Bean name/value aliases");
                if (names.isEmpty()) names = aliases;
                List<String> beanNames = names.isEmpty() ? List.of(method.name) : names.stream().map(String.class::cast).toList();
                for (String name : beanNames)
                    if (name.isBlank() || name.startsWith("&")) throw new IllegalArgumentException("unsupported bean name or alias");
                if (new HashSet<>(beanNames).size() != beanNames.size())
                    throw new IllegalArgumentException("duplicate bean name or alias in one factory");
                AnnotationNode qualifier = annotation(method.visibleAnnotations, QUALIFIER);
                factories.add(new Factory(method, beanNames, (String) value(bean, "initMethod", ""),
                        (String) value(bean, "destroyMethod", "(inferred)"),
                        annotation(method.visibleAnnotations, PRIMARY) != null,
                        qualifier == null ? null : (String) value(qualifier, "value", "")));
            } catch (IllegalArgumentException failure) {
                refused.add(method.name + method.desc + ": " + failure.getMessage());
            }
        }
        Map<String, Long> counts = new HashMap<>();
        for (Factory factory : factories) for (String name : factory.names()) counts.merge(name, 1L, Long::sum);
        factories.removeIf(factory -> {
            String conflict = factory.names().stream().filter(name -> counts.get(name) > 1).findFirst().orElse(null);
            if (conflict == null) return false;
            refused.add(factory.method().name + factory.method().desc + ": duplicate added bean name or alias '" + conflict + "'");
            return true;
        });
        return new Plan(factories, refused);
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations == null ? null : annotations.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static boolean extraAnnotations(List<AnnotationNode> annotations, String... allowed) {
        Set<String> supported = Set.of(allowed);
        return annotations != null && annotations.stream().anyMatch(a -> !supported.contains(a.desc) && !a.desc.equals(DEPRECATED));
    }

    private static Object value(AnnotationNode annotation, String key, Object fallback) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return fallback;
    }

    // The captured lookup is a capability; this entry point stays package-private.
    static Supplier<?> create(Class<?> owner, Supplier<?> currentConfig, Factory factory) throws Throwable {
        if (Type.getArgumentTypes(factory.method().desc).length != 0)
            throw new IllegalArgumentException("parameterized factory requires an argument resolver");
        return create(owner, currentConfig, factory, ignored -> () -> new Object[0]);
    }

    static Supplier<?> create(Class<?> owner, Supplier<?> currentConfig, Factory factory,
                              Function<Method, Supplier<Object[]>> argumentResolver) throws Throwable {
        MethodHandles.Lookup lookup = LookupCapture.get(owner);
        if (lookup == null) throw new IllegalStateException("no captured lookup for " + owner.getName());
        String internal = Type.getInternalName(owner);
        String adapter = internal + "$$ReclazzBean";
        String supplier = "Ljava/util/function/Supplier;";
        String target = InjectedNames.PREFIX + "target";
        String arguments = InjectedNames.PREFIX + "arguments";
        Type[] argumentTypes = Type.getArgumentTypes(factory.method().desc);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, adapter, null,
                "java/lang/Object", new String[]{"java/util/function/Supplier"});
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, target, supplier, null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, arguments, supplier, null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + supplier + supplier + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, target, supplier);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, arguments, supplier);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, adapter, arguments, supplier);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, adapter, target, supplier);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, internal);
        for (int i = 0; i < argumentTypes.length; i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitLdcInsn(i);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitTypeInsn(Opcodes.CHECKCAST, argumentTypes[i].getInternalName());
        }
        Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC,
                "com/onurkat/reclazz/bootstrap/ReclazzBootstrap", "bootstrapMethod",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn(factory.method().name,
                "(" + Type.getDescriptor(owner) + factory.method().desc.substring(1),
                bootstrap, internal, CallSiteAdapter.descHash(factory.method().desc));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor metadata = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                PARAMETERS, factory.method().desc, null, null);
        for (int i = 0; i < argumentTypes.length; i++) {
            String name = null;
            int access = 0;
            if (factory.method().parameters != null && i < factory.method().parameters.size()) {
                var parameter = factory.method().parameters.get(i);
                name = parameter.name;
                access = parameter.access;
            } else if (factory.method().localVariables != null) {
                int slot = i + 1; // all supported parameters occupy one slot
                name = factory.method().localVariables.stream().filter(v -> v.index == slot)
                        .map(v -> v.name).findFirst().orElse(null);
            }
            metadata.visitParameter(name, access);
            if (factory.method().visibleParameterAnnotations != null
                    && i < factory.method().visibleParameterAnnotations.length
                    && factory.method().visibleParameterAnnotations[i] != null)
                for (var annotation : factory.method().visibleParameterAnnotations[i])
                    annotation.accept(metadata.visitParameterAnnotation(i, annotation.desc, true));
        }
        // Read only for parameter metadata; never used to invoke application code.
        metadata.visitCode();
        metadata.visitInsn(Opcodes.ACONST_NULL);
        metadata.visitInsn(Opcodes.ARETURN);
        metadata.visitMaxs(0, 0);
        metadata.visitEnd();
        writer.visitEnd();
        MethodHandles.Lookup hidden = lookup.defineHiddenClass(writer.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
        Class<?>[] parameters = MethodType.fromMethodDescriptorString(factory.method().desc, owner.getClassLoader()).parameterArray();
        Method reflected = hidden.lookupClass().getDeclaredMethod(PARAMETERS, parameters);
        Supplier<Object[]> resolved = argumentResolver.apply(reflected);
        return (Supplier<?>) hidden.findConstructor(hidden.lookupClass(), MethodType.methodType(void.class, Supplier.class, Supplier.class))
                .invoke(currentConfig, resolved);
    }
}
