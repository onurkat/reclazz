/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.bootstrap;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Orika property metadata for added bean accessors. No Orika runtime dependency. */
public final class OrikaBridge {
    private OrikaBridge() { }

    private static final Map<Object, List<Stamp>> refreshed = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> defaultBuilders = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ReferenceQueue<Object> deadPlanKeys = new ReferenceQueue<>();
    private static final Map<IdentityKey, Boolean> defaultPlans = new HashMap<>();
    private static final class IdentityKey extends WeakReference<Object> {
        private final int hash;
        IdentityKey(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object value = get();
            return other instanceof IdentityKey key && value != null && value == key.get();
        }
    }
    private static synchronized boolean defaultPlan(Object plan) {
        IdentityKey dead;
        while ((dead = (IdentityKey) deadPlanKeys.poll()) != null) defaultPlans.remove(dead);
        return Boolean.TRUE.equals(defaultPlans.get(new IdentityKey(plan, null)));
    }
    private static synchronized void rememberPlan(Object plan) {
        defaultPlan(plan);
        defaultPlans.put(new IdentityKey(plan, deadPlanKeys), true);
    }
    private static final ThreadLocal<Boolean> rebuilding = new ThreadLocal<>();
    // The class file a reload applied, by class. Javassist compiles a generated
    // mapper against class files it reads through the loader, and a loader can
    // answer with an older copy: SAP Commerce's finds models.jar before the
    // directory a regen writes, so a regenerated DTO's added getter was "not found".
    private static final Map<Class<?>, byte[]> reloadedBytes = Collections.synchronizedMap(new WeakHashMap<>());
    // The class path entry this bridge put in each pool, by pool and class name.
    private static final Map<Object, Map<String, Object>> insertedPaths = Collections.synchronizedMap(new WeakHashMap<>());

    /** The class file a reload just applied to {@code owner}; null when it carries no added accessors. */
    public static void reloaded(Class<?> owner, byte[] bytes) {
        if (bytes == null) reloadedBytes.remove(owner);
        else reloadedBytes.put(owner, bytes.clone());
    }

    public static final String REMOVED_MAPPING = "application-defined Orika mapping refers to a removed accessor; update the mapping configuration";
    private static volatile BiConsumer<String, Boolean> reporter;

    /** The composition root supplies diagnostics without a bootstrap dependency on the agent. */
    public static void reporter(BiConsumer<String, Boolean> sink) { reporter = sink; }
    private static void report(String subject, boolean active) {
        try { var sink = reporter; if (sink != null) sink.accept(subject, active); }
        catch (Throwable ignored) { /* Diagnostics must not change mapping execution. */ }
    }

    // Only strings and weak references: a value cannot retain a weak factory's loader.
    private record Stamp(WeakReference<Class<?>> a, WeakReference<Class<?>> b,
                         WeakReference<Class<?>> schemaA, WeakReference<Class<?>> schemaB,
                         WeakReference<Object> plan, Set<String> implicit, Set<String> warnings) {
        boolean pair(Class<?> x, Class<?> y) { return a.get() == x && b.get() == y || a.get() == y && b.get() == x; }
        boolean current(Class<?> x, Class<?> sx, Class<?> sy) {
            return a.get() == x ? sameSchema(schemaA, sx) && sameSchema(schemaB, sy)
                    : sameSchema(schemaB, sx) && sameSchema(schemaA, sy);
        }
    }
    private record Refreshed(Object plan, Set<String> implicit, Set<String> warnings) { }
    private static boolean sameSchema(WeakReference<Class<?>> previous, Class<?> current) {
        // A collected old schema is not the same state as a pair that never had one.
        return previous == null ? current == null : current != null && previous.get() == current;
    }

    /** Refresh a cached ordinary mapping plan before the facade consults its strategy cache. */
    public static void beforeMapping(Object facade, Object source, Type sourceType, Type destinationType) {
        if (source == null || Boolean.TRUE.equals(rebuilding.get())) return;
        Class<?> a = raw(sourceType == null ? source.getClass() : sourceType), b = raw(destinationType);
        if (a == null || b == null) return;
        Class<?> schemaA = JacksonBridge.schema(a), schemaB = JacksonBridge.schema(b);
        if (schemaA == null && schemaB == null && refreshed.isEmpty()) return;
        try {
            ClassLoader loader = facade.getClass().getClassLoader();
            Class<?> facadeType = Class.forName("ma.glasnost.orika.impl.MapperFacadeImpl", false, loader);
            var field = facadeType.getDeclaredField("mapperFactory");
            field.setAccessible(true);
            Object factory = field.get(facade);
            if (!factory.getClass().getName().equals("ma.glasnost.orika.impl.DefaultMapperFactory")) return;
            List<Stamp> stamps;
            synchronized (refreshed) { stamps = refreshed.get(factory); }
            if (stamps == null) {
                if (schemaA == null && schemaB == null) return;
                synchronized (refreshed) { stamps = refreshed.computeIfAbsent(factory, ignored -> new ArrayList<>()); }
            }
            synchronized (factory) {
                stamps.removeIf(s -> s.a().get() == null || s.b().get() == null);
                Stamp previous = null;
                for (Stamp stamp : stamps) if (stamp.pair(a, b)) { previous = stamp; break; }
                if (previous == null && schemaA == null && schemaB == null) return;
                if (previous != null && previous.current(a, schemaA, schemaB)) return;
                rebuilding.set(true);
                try {
                    Refreshed result = refreshPlan(factory, sourceType == null ? source.getClass() : sourceType,
                            destinationType, loader, previous);
                    if (previous != null) for (String warning : previous.warnings())
                        if (!result.warnings().contains(warning)) report(warning, false);
                    for (String warning : result.warnings())
                        if (previous == null || !previous.warnings().contains(warning)) report(warning, true);
                    stamps.removeIf(s -> s.pair(a, b));
                    stamps.add(new Stamp(new WeakReference<>(a), new WeakReference<>(b),
                            schemaA == null ? null : new WeakReference<>(schemaA), schemaB == null ? null : new WeakReference<>(schemaB),
                            new WeakReference<>(result.plan()), result.implicit(), result.warnings()));
                } finally { rebuilding.remove(); }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Reclazz could not refresh the added Orika property mapping", failure);
        }
    }

    /** Record the actual byDefault policy, rather than inferring it from existing fields. */
    public static void defaults(Object builder, Object direction, Object[] hints) {
        try {
            Class<?> base = Class.forName("ma.glasnost.orika.metadata.ClassMapBuilder", false, builder.getClass().getClassLoader());
            if (builder.getClass() != base) return;
            var defaults = base.getDeclaredField("defaults");
            defaults.setAccessible(true);
            Object[] configured = (Object[]) defaults.get(builder);
            boolean ordinary = direction instanceof Enum<?> e && e.name().equals("BIDIRECTIONAL")
                    && hints != null && hints.length == 0 && (configured == null || configured.length == 0);
            defaultBuilders.merge(builder, ordinary, (previous, next) -> previous && next);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Reclazz could not retain the Orika default mapping policy", failure);
        }
    }

    public static void copiedPlan(Object map, Object original) {
        if (defaultPlan(original)) rememberPlan(map);
    }

    public static void plan(Object map, Object builder) {
        if (Boolean.TRUE.equals(defaultBuilders.get(builder))) rememberPlan(map);
    }

    private static Class<?> raw(Type type) {
        return type instanceof Class<?> c ? c
                : type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c ? c : null;
    }

    private static Refreshed refreshPlan(Object factory, Type source, Type destination, ClassLoader loader, Stamp previous)
            throws ReflectiveOperationException {
        Class<?> typeFactory = Class.forName("ma.glasnost.orika.metadata.TypeFactory", false, loader);
        Class<?> type = Class.forName("ma.glasnost.orika.metadata.Type", false, loader);
        Class<?> keyType = Class.forName("ma.glasnost.orika.metadata.MapperKey", false, loader);
        Class<?> classMapType = Class.forName("ma.glasnost.orika.metadata.ClassMap", false, loader);
        Method valueOf = typeFactory.getMethod("valueOf", Type.class);
        Object a = valueOf.invoke(null, source), b = valueOf.invoke(null, destination);
        Object key = keyType.getConstructor(type, type).newInstance(a, b);
        Object old = factory.getClass().getMethod("getClassMap", keyType).invoke(factory, key);
        if (old == null) return new Refreshed(null, Set.of(), Set.of());
        // A new application plan owns its whole mapping set, even if it copied our last one.
        Set<String> owned = previous != null && previous.plan().get() == old ? previous.implicit() : Set.of();
        Object oldA = classMapType.getMethod("getAType").invoke(old);
        Object oldB = classMapType.getMethod("getBType").invoke(old);
        Class<?> ownerA = (Class<?>) type.getMethod("getRawType").invoke(oldA);
        Class<?> ownerB = (Class<?>) type.getMethod("getRawType").invoke(oldB);
        Method mappings = classMapType.getMethod("getFieldsMapping");
        Set<?> existing = (Set<?>) mappings.invoke(old);
        Set<Object> merged = new LinkedHashSet<>();
        Set<String> implicit = new LinkedHashSet<>(), warnings = new LinkedHashSet<>();
        Class<?> fieldMap = Class.forName("ma.glasnost.orika.metadata.FieldMap", false, loader);
        Method sourceExpression = fieldMap.getMethod("getSourceExpression");
        Method destinationExpression = fieldMap.getMethod("getDestinationExpression");
        Method getSource = fieldMap.getMethod("getSource"), getDestination = fieldMap.getMethod("getDestination");
        Method excluded = fieldMap.getMethod("isExcluded");
        Class<?> property = Class.forName("ma.glasnost.orika.metadata.Property", false, loader);
        Method getter = property.getMethod("getGetter"), setter = property.getMethod("getSetter");
        for (Object map : existing) {
            String from = (String) sourceExpression.invoke(map), to = (String) destinationExpression.invoke(map);
            String id = from + "\0" + to;
            // Default additions are flat properties. Nested application expressions remain native policy.
            boolean simple = from.equals(root(from)) && to.equals(root(to));
            boolean missing = simple && !Boolean.TRUE.equals(excluded.invoke(map))
                    && (missingAccessor(getSource.invoke(map), ownerA, getter)
                    || missingAccessor(getDestination.invoke(map), ownerB, setter));
            if (missing && owned.contains(id)) continue;
            merged.add(map);
            if (owned.contains(id)) implicit.add(id);
            if (missing) warnings.add(ownerA.getName() + "." + from + " -> " + ownerB.getName() + "." + to);
        }
        if (defaultPlan(old)) {
            Object builder = factory.getClass().getMethod("classMap", type, type).invoke(factory, oldA, oldB);
            Class<?> hint = Class.forName("ma.glasnost.orika.DefaultFieldMapper", false, loader);
            Object hints = java.lang.reflect.Array.newInstance(hint, 0);
            builder.getClass().getMethod("byDefault", hints.getClass()).invoke(builder, hints);
            Object defaults = builder.getClass().getMethod("toClassMap").invoke(builder);
            Set<String> usedA = new HashSet<>(), usedB = new HashSet<>();
            for (Object map : merged) {
                usedA.add(root((String) sourceExpression.invoke(map)));
                usedB.add(root((String) destinationExpression.invoke(map)));
            }
            for (Object map : (Set<?>) mappings.invoke(defaults)) {
                String from = (String) sourceExpression.invoke(map), to = (String) destinationExpression.invoke(map);
                if (!usedA.contains(root(from)) && !usedB.contains(root(to))
                        && !missingAccessor(getSource.invoke(map), ownerA, getter)
                        && !missingAccessor(getDestination.invoke(map), ownerB, setter)) {
                    merged.add(map); implicit.add(from + "\0" + to);
                    usedA.add(root(from)); usedB.add(root(to));
                }
            }
        }
        if (merged.equals(existing)) return new Refreshed(old, Set.copyOf(implicit), Set.copyOf(warnings));
        Object replacement = classMapType.getMethod("copy", Set.class).invoke(old, merged);
        refreshCompiler(factory, ownerA, ownerB, loader);
        if (defaultPlan(old)) rememberPlan(replacement);
        var lookup = factory.getClass().getDeclaredMethod("getRegisteredMapper", keyType);
        lookup.setAccessible(true);
        Object previousMapper = lookup.invoke(factory, key);
        factory.getClass().getMethod("registerClassMap", classMapType).invoke(factory, replacement);
        if (previousMapper != null) {
            var registryField = factory.getClass().getDeclaredField("mappersRegistry");
            registryField.setAccessible(true);
            ((java.util.Collection<?>) registryField.get(factory)).remove(previousMapper);
        }
        Object registered = factory.getClass().getMethod("getClassMap", keyType).invoke(factory, key);
        return new Refreshed(registered, Set.copyOf(implicit), Set.copyOf(warnings));
    }

    private static boolean missingAccessor(Object property, Class<?> owner, Method accessor) throws ReflectiveOperationException {
        String expression = (String) accessor.invoke(property);
        if (expression == null) return false;
        // Orika's resolver cache can itself retain an added property after rollback.
        // Test the current member view, not that cached Property object's existence.
        int arguments = expression.indexOf('(');
        String name = arguments < 0 ? expression : expression.substring(0, arguments);
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.codePointAt(0))
                || !name.codePoints().allMatch(Character::isJavaIdentifierPart)) return false; // native complex expression policy
        if (arguments >= 0) {
            int arity = expression.substring(arguments).equals("()") ? 0 : 1;
            for (Method member : ReflectionBridge.getMethods(owner))
                if (member.getName().equals(name) && member.getParameterCount() == arity) return false;
        } else {
            for (var member : ReflectionBridge.getFields(owner)) if (member.getName().equals(name)) return false;
        }
        return true;
    }

    private static void refreshCompiler(Object factory, Class<?> a, Class<?> b, ClassLoader loader)
            throws ReflectiveOperationException {
        var strategyField = factory.getClass().getDeclaredField("compilerStrategy");
        strategyField.setAccessible(true);
        Object strategy = strategyField.get(factory);
        if (!strategy.getClass().getName().equals("ma.glasnost.orika.impl.generator.JavassistCompilerStrategy")) return;
        var poolField = strategy.getClass().getDeclaredField("classPool");
        poolField.setAccessible(true);
        Object pool = poolField.get(strategy);
        Class<?> poolType = Class.forName("javassist.ClassPool", false, loader);
        Class<?> ctType = Class.forName("javassist.CtClass", false, loader);
        Class<?> pathType = Class.forName("javassist.ClassPath", false, loader);
        Class<?> bytesPathType = Class.forName("javassist.ByteArrayClassPath", false, loader);
        synchronized (pool) {
            Map<String, Object> inserted;
            synchronized (insertedPaths) { inserted = insertedPaths.computeIfAbsent(pool, ignored -> new HashMap<>()); }
            for (Class<?> owner : new Class<?>[]{a, b}) {
                if (owner == null) continue;
                Object ct = poolType.getMethod("getOrNull", String.class).invoke(pool, owner.getName());
                if (ct != null) ctType.getMethod("detach").invoke(ct);
                // A final schema removal must detach and remove the last added-bytecode view too.
                Object previous = inserted.remove(owner.getName());
                if (previous != null) poolType.getMethod("removeClassPath", pathType).invoke(pool, previous);
                byte[] bytes = reloadedBytes.get(owner);
                if (bytes == null) continue;
                Object path = bytesPathType.getConstructor(String.class, byte[].class).newInstance(owner.getName(), bytes);
                poolType.getMethod("insertClassPath", pathType).invoke(pool, path);
                inserted.put(owner.getName(), path);
            }
        }
    }

    private static String root(String expression) {
        if (expression == null) return "";
        int dot = expression.indexOf('.'), bracket = expression.indexOf('[');
        int end = dot < 0 ? bracket : bracket < 0 ? dot : Math.min(dot, bracket);
        return end < 0 ? expression : expression.substring(0, end);
    }

    /** Overlay saved accessor metadata; leave unrelated resolver results untouched. */
    public static Map<?, ?> properties(Map<?, ?> original, Type type, Object resolver) {
        Class<?> owner = type instanceof Class<?> c ? c
                : type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c ? c : null;
        if (owner == null || !JacksonBridge.hasGetters(owner)) return original;

        Map<String, Method> reads = new LinkedHashMap<>();
        Map<String, Method> writes = new LinkedHashMap<>();
        for (Method method : JacksonBridge.getDeclaredMethods(owner)) {
            // The ordinary resolver owns existing properties, annotations and exclusions.
            // Only the saved metadata carrier represents newly added members.
            if (method.getDeclaringClass() == owner || !Modifier.isPublic(method.getModifiers())
                    || Modifier.isStatic(method.getModifiers())) continue;
            String name = method.getName();
            if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
                if (name.startsWith("get") && name.length() > 3) reads.put(decap(name.substring(3)), method);
                else if (name.startsWith("is") && name.length() > 2 && method.getReturnType() == boolean.class)
                    reads.put(decap(name.substring(2)), method);
            } else if (method.getParameterCount() == 1 && method.getReturnType() == void.class
                    && name.startsWith("set") && name.length() > 3) writes.put(decap(name.substring(3)), method);
        }
        if (reads.isEmpty() && writes.isEmpty()) return original;
        try {
            Class<?> builderType = Class.forName("ma.glasnost.orika.metadata.Property$Builder", false,
                    resolver.getClass().getClassLoader());
            Method propertyFor = builderType.getMethod("propertyFor", Class.class, String.class);
            Method propertyType = builderType.getMethod("type", Type.class);
            Method getter = builderType.getMethod("getter", String.class);
            Method setter = builderType.getMethod("setter", String.class);
            Method build = builderType.getMethod("build");
            Map<Object, Object> merged = new LinkedHashMap<>(original);
            Map<String, Method> all = new LinkedHashMap<>(writes);
            all.putAll(reads);
            for (String name : all.keySet()) {
                Method read = reads.get(name), write = writes.get(name);
                // A mismatched accessor pair needs the resolver's normal diagnostics.
                if (read != null && write != null && read.getReturnType() != write.getParameterTypes()[0])
                    throw new IllegalStateException("Incompatible added Orika accessors: " + name);
                Object builder = propertyFor.invoke(null, owner, name);
                propertyType.invoke(builder, read != null ? read.getGenericReturnType() : write.getGenericParameterTypes()[0]);
                if (read != null) getter.invoke(builder, read.getName() + "()");
                if (write != null) setter.invoke(builder, write.getName() + "(%s)");
                merged.put(name, build.invoke(builder));
            }
            return merged;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Reclazz could not expose added Orika properties", failure);
        }
    }

    private static String decap(String value) {
        if (value.length() > 1 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1)))
            return value;
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
