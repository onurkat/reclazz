/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/** Reads condition metadata from the saved factory, which reflection cannot see. */
final class SpringBeanConditions {
    private SpringBeanConditions() { }

    static boolean shouldSkip(byte[] bytes, MethodNode method, Object context, Object factory,
                              ClassLoader springLoader) throws ReflectiveOperationException {
        // A configuration type may be shared by contexts with different child
        // loaders. Conditions belong to the context evaluating this factory.
        ClassLoader applicationLoader = (ClassLoader) factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
        if (applicationLoader == null) throw new IllegalStateException("conditional factory requires an application classloader");
        // Spring's metadata reader can omit an annotation it cannot resolve.
        // A missing Boot API must refuse, never turn a conditional bean into an
        // unconditional one. Resolve through this context's application loader.
        if (method.visibleAnnotations != null) for (var annotation : method.visibleAnnotations) {
            if (AddedBeanAdapter.BOOT_CONDITIONS.contains(annotation.desc))
                Class.forName(Type.getType(annotation.desc).getClassName(), false, applicationLoader);
        }
        // MethodMetadata does not expose a JVM descriptor. Select the exact
        // overload before Spring reads it, preserving its real owner and name.
        // This bytecode is only a metadata view; it is never defined as a class.
        ClassWriter writer = new ClassWriter(0);
        int[] selected = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!name.equals(method.name) || !descriptor.equals(method.desc)) return null;
                selected[0]++;
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        if (selected[0] != 1) throw new IllegalArgumentException("cannot identify the saved conditional factory");

        Class<?> resource = Class.forName("org.springframework.core.io.Resource", false, springLoader);
        Object input = Class.forName("org.springframework.core.io.ByteArrayResource", false, springLoader)
                .getConstructor(byte[].class).newInstance((Object) writer.toByteArray());
        Class<?> readers = Class.forName("org.springframework.core.type.classreading.SimpleMetadataReaderFactory", false, springLoader);
        Object readerFactory = readers.getConstructor(ClassLoader.class).newInstance(applicationLoader);
        Object reader = readers.getMethod("getMetadataReader", resource).invoke(readerFactory, input);
        Class<?> readerType = Class.forName("org.springframework.core.type.classreading.MetadataReader", false, springLoader);
        Object metadata = readerType.getMethod("getAnnotationMetadata").invoke(reader);
        Class<?> annotationMetadata = Class.forName("org.springframework.core.type.AnnotationMetadata", false, springLoader);
        Set<?> factories = (Set<?>) annotationMetadata.getMethod("getAnnotatedMethods", String.class)
                .invoke(metadata, "org.springframework.context.annotation.Bean");
        if (factories.size() != 1) throw new IllegalArgumentException("Spring could not read the saved conditional factory");
        return SpringComponentConditions.shouldSkipMetadata(factories.iterator().next(), context, factory,
                applicationLoader, springLoader, "REGISTER_BEAN");
    }
}
