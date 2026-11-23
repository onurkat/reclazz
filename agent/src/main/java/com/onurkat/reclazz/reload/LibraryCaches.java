/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import java.util.HashSet;
import java.util.Set;

/**
 * R35: the library caches of a class's members that a reload changing members must clear, in one place. Only a reload
 * that adds or removes fields or methods clears them: a body-only save leaves every cache warm.
 */
public final class LibraryCaches {
    private LibraryCaches() { }

    static void flush(Class<?> owner) {
        GsonCaches.flush();
        SpringBeanPropertyCaches.flush(owner);
        SpelCaches.flush();
        ElCaches.flush();
        IntrospectionCaches.flush(owner);
        OccFieldSetCaches.flush(owner);
    }

    /** The visible shape each class had at its last reload (added members included), to see what a reload changes. */
    private static final java.util.Map<Class<?>, Set<String>> SHAPES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Stock JVM path, after the reload published its members: flushes when the class's visible fields or methods differ
     * from what they were (added members count; removing them again counts too, though the bytes then match the original).
     */
    static void flushIfMembersChanged(Class<?> owner) {
        Set<String> now = shape(owner.getDeclaredFields(), owner.getDeclaredMethods());
        Set<String> before = SHAPES.get(owner);
        if (before == null) before = shape(com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredFields(owner),
                com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredMethods(owner));
        SHAPES.put(owner, now);
        if (!now.equals(before)) flush(owner);
    }

    private static Set<String> shape(java.lang.reflect.Field[] fields, java.lang.reflect.Method[] methods) {
        Set<String> shape = new HashSet<>();
        for (var f : fields) if (!f.isSynthetic()) shape.add(f.getName() + ":" + org.objectweb.asm.Type.getDescriptor(f.getType()));
        for (var m : methods)
            if (!m.isSynthetic() && !com.onurkat.reclazz.bootstrap.InjectedNames.isInjected(m.getName()))
                shape.add(m.getName() + org.objectweb.asm.Type.getMethodDescriptor(m));
        return shape;
    }

    /**
     * Enhanced-redefinition path (JBR/DCEVM), where the JVM class itself changes: compares the loaded class's declared
     * fields and methods with the new bytes. Call before the redefinition; flush with {@link #flushAfter} once it succeeded.
     */
    public static boolean membersChange(Class<?> owner, byte[] bytes) {
        try {
            Set<String> loaded = new HashSet<>();
            for (var f : com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredFields(owner))
                if (!f.isSynthetic()) loaded.add(f.getName() + ":" + org.objectweb.asm.Type.getDescriptor(f.getType()));
            for (var m : com.onurkat.reclazz.bootstrap.ReflectionBridge.physicalDeclaredMethods(owner))
                if (!m.isSynthetic() && !com.onurkat.reclazz.bootstrap.InjectedNames.isInjected(m.getName())) loaded.add(m.getName() + org.objectweb.asm.Type.getMethodDescriptor(m));
            Set<String> next = new HashSet<>();
            new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    if ((access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) == 0) next.add(name + ":" + desc);
                    return null;
                }
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if ((access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) == 0 && !name.startsWith("<") && !com.onurkat.reclazz.bootstrap.InjectedNames.isInjected(name)) next.add(name + desc);
                    return null;
                }
            }, org.objectweb.asm.ClassReader.SKIP_CODE);
            return !loaded.equals(next);
        } catch (Throwable unreadable) {
            return true;
        }
    }

    public static void flushAfter(Class<?> owner) { flush(owner); }
}
