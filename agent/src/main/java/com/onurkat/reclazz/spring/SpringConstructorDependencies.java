/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Named constructor-cache repair and a bounded plain Helper -> leaf Owner snapshot. */
final class SpringConstructorDependencies {
    private static final String FACTORY =
            "org.springframework.beans.factory.support.DefaultListableBeanFactory";
    private static final Set<String> VERSIONS = Set.of("5.3.39", "6.1.14", "5.3.43", "6.2.12");
    private static final Set<String> DEFINITIONS = Set.of(
            "org.springframework.beans.factory.support.RootBeanDefinition",
            "org.springframework.beans.factory.support.GenericBeanDefinition",
            "org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition",
            "org.springframework.context.annotation.ScannedGenericBeanDefinition",
            "org.springframework.context.support.GenericApplicationContext$ClassDerivedBeanDefinition");

    private SpringConstructorDependencies() { }

    /**
     * Read-only eligibility check. Unsupported creation policies retain their existing reload path;
     * this does not authorize an indirect cascade through them.
     */
    static Prepared prepare(List<Object> contexts, Object context, String name) {
        try {
            if (contexts.stream().noneMatch(c -> c == context)
                    || !Boolean.TRUE.equals(call(context, "isActive")))
                return null;
            Object factory = SpringBeans.getBeanFactory(context);
            if (!known(factory)) return null;
            Object parent = call(factory, "getParentBeanFactory");
            if (parent != null && (!known(parent) || call(parent, "getParentBeanFactory") != null
                    || contexts.stream().noneMatch(c -> SpringBeans.getBeanFactory(c) == parent)))
                return null;
            if (!Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))) return null;
            Object raw = call(factory, "getBeanDefinition", name);
            if (!DEFINITIONS.contains(raw.getClass().getName()) || !plain(raw)) return null;
            Object singleton = call(factory, "getSingleton", name);
            if (singleton == null) return null;
            Class<?> type = singleton.getClass();
            if (!type.getName().equals(call(raw, "getBeanClassName"))) return null;
            Class<?> factoryBean = Class.forName("org.springframework.beans.factory.FactoryBean", false,
                    factory.getClass().getClassLoader());
            if (factoryBean.isInstance(singleton)) return null;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length != 1 || constructors[0].getParameterCount() == 0) return null;
            if (parent != null && (constructors[0].getParameterCount() != 1
                    || constructors[0].getParameterAnnotations()[0].length != 0
                    || ((String[]) call(factory, "getAliases", name)).length != 0)) return null;
            Object merged = call(factory, "getMergedBeanDefinition", name);
            if (!merged.getClass().getName().equals("org.springframework.beans.factory.support.RootBeanDefinition")
                    || !plain(merged)) return null;
            if (!(Reflect.readField(merged, "resolvedConstructorOrFactoryMethod") instanceof Constructor<?> cached)
                    || !cached.equals(constructors[0])
                    || !Arrays.equals(cached.getDeclaredAnnotations(), constructors[0].getDeclaredAnnotations())
                    || !Arrays.deepEquals(cached.getParameterAnnotations(), constructors[0].getParameterAnnotations())
                    || Reflect.readField(merged, "resolvedConstructorArguments") != null
                    || !(Reflect.readField(merged, "preparedConstructorArguments") instanceof Object[] prepared)
                    || prepared.length != cached.getParameterCount()) return null;
            Method invalidate = Reflect.findMethod(factory.getClass(), "clearMergedBeanDefinition", String.class);
            return invalidate == null ? null : new Prepared(List.copyOf(contexts), context,
                    factory, name, singleton, merged, invalidate);
        } catch (Exception | LinkageError unavailable) {
            return null;
        }
    }

    private static boolean known(Object factory) {
        return factory != null && factory.getClass().getName().equals(FACTORY)
                && VERSIONS.contains(factory.getClass().getPackage().getImplementationVersion());
    }

    private record Scope(Object context, Object factory) { }

    /** Existing instance and metadata identities; never resolve a cold singleton in preflight. */
    record Bean(Object context, Object factory, String name, Object instance, Object raw, Object merged) {
        boolean sameName(Bean other) { return other != null && factory == other.factory && name.equals(other.name); }
        boolean sameSnapshot(Bean other) {
            return sameName(other) && context == other.context && instance == other.instance
                    && raw == other.raw && merged == other.merged;
        }
    }

    private static java.util.List<Scope> scopes(List<Object> contexts) throws Exception {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        var result = new java.util.ArrayList<Scope>();
        for (Object context : contexts) {
            if (!Boolean.TRUE.equals(call(context, "isActive"))) continue;
            Object factory = SpringBeans.getBeanFactory(context);
            if (factory != null && seen.add(factory)) result.add(new Scope(context, factory));
        }
        return result;
    }

    /** Local shadow wins; a same spelling in another root is never the changed source. */
    private static Object source(Object factory, String name) throws Exception {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        while (factory != null) {
            if (!seen.add(factory)) throw new IllegalStateException("cyclic parent factory");
            if (Boolean.TRUE.equals(call(factory, "containsLocalBean", name))) return factory;
            factory = call(factory, "getParentBeanFactory");
        }
        return null;
    }

    private static java.util.List<Bean> dependents(List<Scope> scopes, Object factory, String name) throws Exception {
        var result = new java.util.ArrayList<Bean>();
        for (Scope scope : scopes) {
            if (source(scope.factory, name) != factory) continue;
            for (String dependent : (String[]) call(scope.factory, "getDependentBeans", name)) {
                if (!known(scope.factory)) throw new IllegalStateException("custom dependent factory");
                if (scope.factory != factory && call(scope.factory, "getParentBeanFactory") != factory)
                    throw new IllegalStateException("multilevel dependent context requires restart");
                result.add(snapshot(scope, dependent));
            }
        }
        return result;
    }

    private static Bean snapshot(Scope scope, String name) throws Exception {
        if (!Boolean.TRUE.equals(call(scope.factory, "containsBeanDefinition", name)))
            throw new IllegalStateException("dependent is not a local definition: " + name);
        Object instance = call(scope.factory, "getSingleton", name);
        if (instance == null) throw new IllegalStateException("dependent singleton is absent: " + name);
        return new Bean(scope.context, scope.factory, name, instance,
                call(scope.factory, "getBeanDefinition", name), call(scope.factory, "getMergedBeanDefinition", name));
    }

    private static boolean simple(Bean bean) throws Exception {
        if (!known(bean.factory) || !DEFINITIONS.contains(bean.raw.getClass().getName()) || !plain(bean.raw)
                || !bean.merged.getClass().getName().equals("org.springframework.beans.factory.support.RootBeanDefinition")
                || !plain(bean.merged) || !bean.instance.getClass().getName().equals(call(bean.raw, "getBeanClassName"))
                || ((String[]) call(bean.factory, "getAliases", bean.name)).length != 0) return false;
        Class<?> type = bean.instance.getClass();
        // Active callbacks, infrastructure, proxy targets and configured lifecycle are separate owners.
        if (type.getSuperclass() != Object.class || type.getInterfaces().length != 0) return false;
        for (String getter : List.of("getInitMethodName", "getDestroyMethodName")) {
            if (call(bean.raw, getter) != null || call(bean.merged, getter) != null) return false;
        }
        for (var field : com.onurkat.reclazz.bootstrap.ReflectionBridge.getDeclaredFields(type))
            if (field.getDeclaredAnnotations().length != 0) return false;
        for (var method : com.onurkat.reclazz.bootstrap.ReflectionBridge.getDeclaredMethods(type))
            if (method.getDeclaredAnnotations().length != 0) return false;
        for (var annotation : type.getDeclaredAnnotations()) {
            String name = annotation.annotationType().getName();
            if (!name.equals("org.springframework.stereotype.Component") && !name.equals("org.springframework.stereotype.Service"))
                return false;
        }
        return type.getDeclaredConstructors().length == 1;
    }

    /** Only the already measured one-reference constructor, not scalar/property or qualified owners. */
    private static boolean leafConstructor(Bean bean) {
        Constructor<?>[] constructors = bean.instance.getClass().getDeclaredConstructors();
        return constructors.length == 1 && constructors[0].getParameterCount() == 1
                && !constructors[0].getParameterTypes()[0].isPrimitive()
                && constructors[0].getParameterAnnotations()[0].length == 0;
    }

    private static Bean dependency(List<Scope> scopes, Bean owner) throws Exception {
        String[] names = (String[]) call(owner.factory, "getDependenciesForBean", owner.name);
        if (names.length != 1) throw new IllegalStateException("plain owner requires one native dependency");
        Object factory = source(owner.factory, names[0]);
        if (factory != owner.factory && call(owner.factory, "getParentBeanFactory") != factory)
            throw new IllegalStateException("plain owner requires a direct parent or local dependency");
        Scope scope = scopes.stream().filter(s -> s.factory == factory).findFirst().orElseThrow();
        Bean helper = snapshot(scope, names[0]);
        if (!simple(helper) || helper.instance.getClass().getDeclaredConstructors()[0].getParameterCount() != 0
                || !owner.instance.getClass().getDeclaredConstructors()[0].getParameterTypes()[0].isInstance(helper.instance))
            throw new IllegalStateException("unsupported plain constructor dependency");
        return helper;
    }

    /** Read-only enrollment. A known affected but unsupported closure throws instead of falling through. */
    static Ordinary prepareOrdinary(List<Object> contexts, Object context, String name) throws Exception {
        try {
            return inspectOrdinary(contexts, context, name);
        } catch (Exception refused) {
            com.onurkat.reclazz.ui.RestartLedger.note(name,
                    "ordinary constructor refresh refused before retirement; restart required: "
                            + com.onurkat.reclazz.ui.Failures.describe(refused));
            throw refused;
        }
    }

    private static Ordinary inspectOrdinary(List<Object> contexts, Object context, String name) throws Exception {
        Object factory = SpringBeans.getBeanFactory(context);
        if (!known(factory) || !Boolean.TRUE.equals(call(factory, "containsBeanDefinition", name))
                || call(factory, "getSingleton", name) == null) return null;
        var scopes = scopes(contexts);
        Scope selected = scopes.stream().filter(s -> s.factory == factory).findFirst().orElseThrow();
        Bean root = snapshot(selected, name);
        var dependents = dependents(scopes, factory, name);
        if (dependents.isEmpty()) {
            if (!simple(root) || !leafConstructor(root)) return null;
            // Stable qualified/scalar or unsupported creation policies keep the stage1 route.
            Bean helper;
            try { helper = dependency(scopes, root); }
            catch (IllegalStateException | java.util.NoSuchElementException outsideBound) { return null; }
            Prepared cache = prepare(contexts, root.context, name);
            if (cache == null) throw new IllegalStateException("plain owner constructor cache is unsupported or changed");
            return new Ordinary(List.copyOf(contexts), root, null, helper, cache);
        }
        if (dependents.stream().noneMatch(SpringConstructorDependencies::leafConstructor)) return null;
        if (dependents.size() != 1 || !simple(root)
                || root.instance.getClass().getDeclaredConstructors()[0].getParameterCount() != 0)
            throw new IllegalStateException("ordinary constructor refresh requires one plain helper and one leaf owner");
        Bean leaf = dependents.get(0);
        if (!simple(leaf) || !leafConstructor(leaf) || !dependents(scopes, leaf.factory, leaf.name).isEmpty())
            throw new IllegalStateException("unsupported ordinary constructor dependent closure");
        Bean actual = dependency(scopes, leaf);
        if (!actual.sameSnapshot(root)) throw new IllegalStateException("constructor source ownership changed");
        Prepared cache = prepare(contexts, leaf.context, leaf.name);
        if (cache == null) throw new IllegalStateException("dependent constructor cache is unsupported or changed");
        return new Ordinary(List.copyOf(contexts), root, leaf, null, cache);
    }

    record Ordinary(List<Object> contexts, Bean root, Bean leaf, Bean dependency, Prepared cache) {
        boolean covers(Bean bean) { return root.sameName(bean) || leaf != null && leaf.sameName(bean); }

        void validate(List<Object> currentContexts) throws Exception {
            var previous = scopes(contexts);
            var currentScopes = scopes(currentContexts);
            if (previous.size() != currentScopes.size() || previous.stream().anyMatch(p ->
                    currentScopes.stream().noneMatch(c -> c.factory == p.factory)))
                throw new IllegalStateException("captured constructor contexts changed");
            Ordinary current = prepareOrdinary(currentContexts, root.context, root.name);
            if (current == null || !root.sameSnapshot(current.root)
                    || (leaf == null ? current.leaf != null : !leaf.sameSnapshot(current.leaf))
                    || (dependency == null ? current.dependency != null : !dependency.sameSnapshot(current.dependency)))
                throw new IllegalStateException("constructor singleton or metadata changed after preflight");
        }

        void recreate(List<Object> currentContexts, java.util.IdentityHashMap<Object, Object> replacements) throws Exception {
            validate(currentContexts);
            cache.apply();
            try {
                Object[] pair = SpringBeanReloader.destroyAndRefreshBean(root.context, root.name);
                if (pair == null || pair[1] == null || pair[1] == root.instance
                        || pair[1].getClass() != root.instance.getClass())
                    throw new IllegalStateException("plain singleton was not reconstructed");
                replacements.put(root.instance, pair[1]);
                if (leaf != null) {
                    Object stillLive = call(leaf.factory, "getSingleton", leaf.name);
                    if (stillLive != null && stillLive != leaf.instance)
                        throw new IllegalStateException("dependent instance changed during reconstruction");
                    // Same-factory native destruction may already have retired this exact leaf.
                    if (stillLive != null) call(leaf.factory, "destroySingleton", leaf.name);
                    Object fresh = call(leaf.context, "getBean", leaf.name);
                    if (fresh == null || fresh == leaf.instance || fresh.getClass() != leaf.instance.getClass())
                        throw new IllegalStateException("leaf changed its plain creation policy during reconstruction");
                    replacements.put(leaf.instance, fresh);
                }
            } catch (Exception failure) {
                com.onurkat.reclazz.ui.RestartLedger.note(root.instance.getClass().getName(),
                        "ordinary constructor refresh partially reconstructed its beans; restart required");
                throw failure;
            }
        }
    }

    private static boolean plain(Object definition) throws Exception {
        return Boolean.TRUE.equals(call(definition, "isSingleton"))
                && !Boolean.TRUE.equals(call(definition, "isAbstract"))
                && call(definition, "getParentName") == null
                && call(definition, "getFactoryMethodName") == null
                && call(definition, "getFactoryBeanName") == null
                && call(definition, "getInstanceSupplier") == null
                && Boolean.FALSE.equals(call(definition, "hasConstructorArgumentValues"))
                && Boolean.FALSE.equals(call(definition, "hasMethodOverrides"))
                && call(definition, "getQualifiers") instanceof Set<?> qualifiers && qualifiers.isEmpty();
    }

    private static Object call(Object target, String name, String... argument) throws Exception {
        Method method = argument.length == 0 ? Reflect.findMethod(target.getClass(), name)
                : Reflect.findMethod(target.getClass(), name, String.class);
        if (method == null) throw new NoSuchMethodException(name);
        return argument.length == 0 ? method.invoke(target) : method.invoke(target, argument[0]);
    }

    record Prepared(List<Object> contexts, Object context, Object factory, String name,
                    Object singleton, Object merged, Method invalidate) {
        void apply() throws Exception {
            Prepared current = prepare(contexts, context, name);
            if (current == null || current.factory != factory || current.singleton != singleton
                    || current.merged != merged)
                throw new IllegalStateException("constructor owner changed after preflight: " + name);
            // The next native resolution discovers the CURRENT dependencies. Never replay old edge names.
            invalidate.invoke(factory, name);
        }
    }
}
