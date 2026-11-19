/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.EnumSurgery;
import com.onurkat.reclazz.bootstrap.FieldStore;
import com.onurkat.reclazz.bootstrap.ReflectionBridge;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;

/** One accepted save; table evaluation completes before its first write. */
public final class SwitchMapRefresh {
    private SwitchMapRefresh() { }
    private static final class Save {
        final Map<Class<?>, SwitchMapPlan> plans = new LinkedHashMap<>();
        final Set<Class<?>> accepted = new LinkedHashSet<>(), successful = new LinkedHashSet<>();
        final Map<Class<?>, String> failures = new LinkedHashMap<>();
        final Map<Class<?>, Set<Class<?>>> dependents = new LinkedHashMap<>();
        final Map<Class<?>, Set<Class<?>>> dependencies = new LinkedHashMap<>();
        final Map<Class<?>, byte[]> bytes = new LinkedHashMap<>();
        final Set<Class<?>> failed = new LinkedHashSet<>();
        boolean completed;
    }
    private static final ThreadLocal<Save> current = new ThreadLocal<>();
    private static final class Applied { volatile String digest; }
    private static final ClassValue<Applied> applied = new ClassValue<>() {
        @Override protected Applied computeValue(Class<?> owner) { return new Applied(); }
    };
    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static boolean alreadyApplied(Class<?> owner, byte[] bytes) {
        return owner != null && digest(bytes).equals(applied.get(owner).digest);
    }
    public static void mutationStarting(Class<?> owner) { if (owner != null) applied.get(owner).digest = null; }
    public static String dependencyFailure(Class<?> owner, StructuralReloader structural) {
        Save save = current.get(); if (save == null || owner == null) return null;
        Set<Class<?>> visited = new HashSet<>(); java.util.ArrayDeque<Class<?>> pending = new java.util.ArrayDeque<>();
        pending.add(owner);
        while (!pending.isEmpty()) for (Class<?> dependency : save.dependencies.getOrDefault(pending.removeFirst(), Set.of())) {
            if (!visited.add(dependency)) continue;
            if (save.failed.contains(dependency) || (structural != null
                    && structural.nativeOutcome(dependency) == StructuralReloader.NativeOutcome.FAILED))
                return "switch table dependency failed; restart to apply";
            pending.add(dependency);
        }
        return null;
    }
    public static void begin(Map<String, byte[]> bytes, Map<String, SwitchMapPlan> plans,
                             Function<String, Class<?>> loaded) {
        if (current.get() != null) throw new IllegalStateException("Nested switch-map save");
        Save save = new Save(); Map<Class<?>, byte[]> candidates = new LinkedHashMap<>();
        bytes.forEach((name, value) -> {
            Class<?> owner = loaded.apply(name);
            if (owner != null) { candidates.put(owner, value); save.accepted.add(owner); save.bytes.put(owner, value.clone()); }
        });
        plans.forEach((name, plan) -> { Class<?> owner = loaded.apply(name); if (owner != null) save.plans.put(owner, plan); });
        bytes.forEach((name, value) -> {
            Class<?> user = loaded.apply(name);
            if (user != null) for (String reference : SwitchMapPlan.references(value)) {
                Class<?> holder = loaded.apply(reference.replace('/', '.'));
                if (holder != null && holder != user && save.accepted.contains(holder)) {
                    save.dependents.computeIfAbsent(holder, key -> new LinkedHashSet<>()).add(user);
                    save.dependencies.computeIfAbsent(user, key -> new LinkedHashSet<>()).add(holder);
                }
            }
        });
        save.plans.forEach((holder, plan) -> {
            for (var table : plan.tables()) {
                Class<?> type = loaded.apply(table.enumName().replace('/', '.'));
                if (type != null && type != holder && save.accepted.contains(type)) {
                    save.dependencies.computeIfAbsent(holder, key -> new LinkedHashSet<>()).add(type);
                    save.dependents.computeIfAbsent(type, key -> new LinkedHashSet<>()).add(holder);
                }
            }
        });
        AddedStatics.begin(candidates); current.set(save);
    }
    public static void result(Class<?> owner, boolean successful) {
        Save save = current.get();
        if (save != null && owner != null) {
            if (successful) save.successful.add(owner);
            else { save.successful.remove(owner); save.failed.add(owner); }
        }
    }
    private record Write(Class<?> owner, String name, Field field, int[] table) { }
    public static void complete(StructuralReloader structural) {
        Save save = current.get(); if (save == null || save.completed) return;
        save.completed = true;
        if (structural != null) save.successful.removeIf(owner -> structural.nativeOutcome(owner) == StructuralReloader.NativeOutcome.FAILED);
        java.util.ArrayDeque<Class<?>> failed = new java.util.ArrayDeque<>();
        for (Class<?> owner : save.accepted) if (!save.successful.contains(owner)) failed.add(owner);
        Set<Class<?>> visited = new HashSet<>();
        while (!failed.isEmpty()) {
            Class<?> owner = failed.removeFirst(); if (!visited.add(owner)) continue;
            save.failures.put(owner, "switch table dependency redefinition failed; restart to apply");
            failed.addAll(save.dependents.getOrDefault(owner, Set.of()));
        }
        List<Write> writes = new ArrayList<>();
        try {
            for (var item : save.plans.entrySet()) if (save.successful.contains(item.getKey()) && !save.failures.containsKey(item.getKey()))
                writes.addAll(evaluate(item.getKey(), item.getValue()));
            for (Write write : writes) {
                if (write.field() != null) EnumSurgery.writeSwitchTable(write.field(), write.table());
                else FieldStore.putStaticExtField(write.owner(), write.name(), "[I", write.table());
            }
        } catch (Throwable failure) {
            for (Class<?> owner : save.accepted) save.failures.put(owner,
                    "switch table could not be refreshed; restart to apply: " + failure.getClass().getSimpleName());
        }
    }
    public static String failure(Class<?> owner) {
        Save save = current.get(); return save == null ? null : save.failures.get(owner);
    }
    public static void end() {
        Save save = current.get();
        try {
            Set<Class<?>> successful = save == null ? Set.of() : new HashSet<>(save.successful);
            if (save != null) successful.removeAll(save.failures.keySet());
            AddedStatics.finish(successful);
            if (save != null && save.completed) for (Class<?> owner : successful) {
                byte[] bytes = save.bytes.get(owner);
                if (bytes != null) applied.get(owner).digest = digest(bytes);
            }
        } finally { current.remove(); }
    }
    public static boolean matches(Class<?> owner, SwitchMapPlan plan) {
        if (owner == null) return false;
        try {
            // A currently absent case may be appended later in this same save.
            // It must not make a changed holder appear equal to the old table.
            for (var table : plan.tables()) {
                Class<?> type = Class.forName(table.enumName().replace('/', '.'), false, owner.getClassLoader());
                Object[] values = type.getEnumConstants();
                if (values == null) return false;
                Set<String> names = new HashSet<>();
                for (Object value : values) names.add(((Enum<?>) value).name());
                for (var entry : table.entries()) if (!names.contains(entry.constant())) return false;
            }
            for (Write write : evaluate(owner, plan)) {
                int[] live = write.field() == null ? (int[]) FieldStore.getStaticExtField(owner, write.name(), "[I")
                        : EnumSurgery.readSwitchTable(write.field());
                if (!Arrays.equals(live, write.table())) return false;
            }
            return true;
        } catch (Throwable unavailable) { return false; }
    }
    private static List<Write> evaluate(Class<?> owner, SwitchMapPlan plan) throws ClassNotFoundException {
        Map<String, Field> physical = new HashMap<>();
        for (Field field : ReflectionBridge.physicalDeclaredFields(owner))
            if (field.getType() == int[].class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) physical.put(field.getName(), field);
        List<Write> writes = new ArrayList<>();
        for (var table : plan.tables()) {
            Class<?> enumType = Class.forName(table.enumName().replace('/', '.'), false, owner.getClassLoader());
            Object[] constants = enumType.getEnumConstants();
            if (constants == null) throw new IllegalArgumentException("Switch target is not an enum");
            Map<String, Integer> ordinals = new HashMap<>();
            for (Object constant : constants) { Enum<?> value = (Enum<?>) constant; ordinals.put(value.name(), value.ordinal()); }
            int[] values = new int[constants.length];
            for (var entry : table.entries()) {
                Integer ordinal = ordinals.get(entry.constant());
                if (ordinal != null) values[ordinal] = entry.branch();
            }
            writes.add(new Write(owner, table.field(), physical.get(table.field()), values));
        }
        return writes;
    }
}
