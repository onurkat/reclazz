/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.ReflectionBridge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;
import java.util.function.Supplier;

/** Defining-class ownership; speculative keys exist only in this thread's accepted save. */
public final class AddedStatics {
    private AddedStatics() { }
    private static final Map<Class<?>, Set<String>> published = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Map<Class<?>, Set<String>>> save = new ThreadLocal<>();
    private static final ThreadLocal<Class<?>> generating = new ThreadLocal<>();
    public static void begin(Map<Class<?>, byte[]> candidates) {
        if (save.get() != null) throw new IllegalStateException("Nested added-static save");
        Map<Class<?>, Set<String>> staged = new LinkedHashMap<>();
        candidates.forEach((owner, bytes) -> {
            Set<String> physical = new HashSet<>();
            for (var field : ReflectionBridge.physicalDeclaredFields(owner)) physical.add(field.getName() + ":" + field.getType().descriptorString());
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            Set<String> added = new HashSet<>();
            for (var field : node.fields) if ((field.access & Opcodes.ACC_STATIC) != 0
                    && !physical.contains(field.name + ":" + field.desc)) added.add(field.name + ":" + field.desc);
            staged.put(owner, Set.copyOf(added));
        });
        save.set(staged);
    }
    public static void finish(Set<Class<?>> successful) {
        try {
            Map<Class<?>, Set<String>> staged = save.get();
            if (staged != null) staged.forEach((owner, keys) -> { if (successful.contains(owner)) published.put(owner, keys); });
        } finally { save.remove(); }
    }
    public static <T> T generating(Class<?> owner, Supplier<T> action) {
        Class<?> previous = generating.get(); generating.set(owner);
        try { return action.get(); }
        finally { if (previous == null) generating.remove(); else generating.set(previous); }
    }
    public static boolean contains(String internalOwner, String name, String descriptor) {
        Class<?> caller = generating.get();
        if (caller == null) return false;
        try {
            Class<?> owner = Class.forName(internalOwner.replace('/', '.'), false, caller.getClassLoader());
            String key = name + ":" + descriptor;
            Map<Class<?>, Set<String>> staged = save.get();
            return (staged != null && staged.getOrDefault(owner, Set.of()).contains(key))
                    || published.getOrDefault(owner, Set.of()).contains(key);
        } catch (ClassNotFoundException absent) { return false; }
    }
}
