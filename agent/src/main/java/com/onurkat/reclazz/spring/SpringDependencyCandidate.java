/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import static org.objectweb.asm.Opcodes.*;
import java.lang.reflect.Method;

/** Gives the application's Spring resolver the saved argument type and its explicit qualifier. */
final class SpringDependencyCandidate extends ClassLoader {
    private SpringDependencyCandidate(ClassLoader parent) { super(parent); }

    static Object descriptor(Class<?> owner, Class<?> dependency, String qualifier, ClassLoader spring) throws Exception {
        ClassWriter w = new ClassWriter(0);
        String name = "com.onurkat.reclazz.metadata.ConstructorArgument";
        w.visit(V17, ACC_PUBLIC | ACC_SYNTHETIC, name.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "argument", "(" + Type.getDescriptor(dependency) + ")V", null, null);
        if (qualifier != null) {
            var annotation = m.visitParameterAnnotation(0, "Lorg/springframework/beans/factory/annotation/Qualifier;", true);
            annotation.visit("value", qualifier);
            annotation.visitEnd();
        }
        m.visitCode();
        m.visitInsn(RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
        w.visitEnd();
        byte[] bytes = w.toByteArray();
        var loader = new SpringDependencyCandidate(owner.getClassLoader());
        Method metadata = loader.defineClass(name, bytes, 0, bytes.length).getMethod("argument", dependency);
        Class<?> parameter = Class.forName("org.springframework.core.MethodParameter", false, spring);
        Object p = parameter.getConstructor(Method.class, int.class).newInstance(metadata, 0);
        return Class.forName("org.springframework.beans.factory.config.DependencyDescriptor", false, spring)
                .getConstructor(parameter, boolean.class, boolean.class).newInstance(p, true, false);
    }

    static boolean matches(Object factory, String name, Object incoming, Object descriptor) throws Exception {
        ClassLoader spring = factory.getClass().getClassLoader();
        Class<?> dep = Class.forName("org.springframework.beans.factory.config.DependencyDescriptor", false, spring);
        if (incoming == null) return (boolean)factory.getClass().getMethod("isAutowireCandidate", String.class, dep)
                .invoke(factory, name, descriptor);
        Object resolver = factory.getClass().getMethod("getAutowireCandidateResolver").invoke(factory);
        Class<?> holder = Class.forName("org.springframework.beans.factory.config.BeanDefinitionHolder", false, spring);
        Class<?> bd = Class.forName("org.springframework.beans.factory.config.BeanDefinition", false, spring);
        // The live factory normally hands its resolver a merged root definition.
        // Build the same root for this parentless component without registering it.
        Class<?> root = Class.forName("org.springframework.beans.factory.support.RootBeanDefinition", false, spring);
        Object merged = root.getConstructor().newInstance();
        root.getMethod("overrideFrom", bd).invoke(merged, incoming);
        Object prospective = holder.getConstructor(bd, String.class).newInstance(merged, name);
        return (boolean)resolver.getClass().getMethod("isAutowireCandidate", holder, dep).invoke(resolver, prospective, descriptor);
    }
}
