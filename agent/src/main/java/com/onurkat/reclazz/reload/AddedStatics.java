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
    private record Generation(Class<?> caller, com.onurkat.reclazz.util.ClassLookup.LoadedClasses loaded) { }
    private static final ThreadLocal<Generation> generating = new ThreadLocal<>();
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
            if (staged != null) staged.forEach((owner, keys) -> {
                if (successful.contains(owner)) {
                    if (keys.isEmpty()) published.remove(owner);
                    else published.put(owner, keys);
                }
            });
        } finally { save.remove(); }
    }
    public static <T> T generating(Class<?> owner, com.onurkat.reclazz.util.ClassLookup.LoadedClasses loaded, Supplier<T> action) {
        Generation previous = generating.get(); generating.set(new Generation(owner,loaded));
        try { return action.get(); }
        finally { if (previous == null) generating.remove(); else generating.set(previous); }
    }
    public static boolean contains(String internalOwner, String name, String descriptor) {
        Generation generation = generating.get();
        Class<?> caller = generation == null ? null : generation.caller();
        if (caller == null) return false;
        Map<Class<?>, Set<String>> staged = save.get();
        // A save may carry owners with empty sets so successful removals can
        // retire old publication. Those tombstones do not require a census.
        if ((staged == null || staged.values().stream().allMatch(Set::isEmpty)) && published.isEmpty()) return false;
        {
            Class<?> owner = generation.loaded().find(internalOwner.replace('/', '.'), caller.getClassLoader());
            if (owner == null) return false;
            String key = name + ":" + descriptor;
            return (staged != null && staged.getOrDefault(owner, Set.of()).contains(key))
                    || published.getOrDefault(owner, Set.of()).contains(key);
        }
    }
}
