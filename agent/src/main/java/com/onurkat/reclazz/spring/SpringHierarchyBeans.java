/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.transform.CreationBaseline;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Selects inherited factory definitions for already-created lite configuration singletons. */
final class SpringHierarchyBeans {
    static final String BEAN = "Lorg/springframework/context/annotation/Bean;";
    private static final String CONFIG = "org.springframework.context.annotation.Configuration";
    record Scope(Object context, Object factory, String name, Object receiver,
                 SpringConfigurationOwners.Inventory inventory) { }
    record Selection(Class<?> declaring, Method resolved, AddedBeanAdapter.Factory method, byte[] classfile) { }

    static List<Scope> scopes(List<?> contexts, Class<?> changed) throws Exception {
        List<Scope> out = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object context : contexts) {
            Object factory = SpringBeans.getBeanFactory(context);
            if (!seen.add(factory)) continue;
            var inv = SpringConfigurationOwners.inventory(factory);
            if (inv == null || !inv.refreshed || !(inv.baseline.containsKey(changed.getName())
                    || inv.configurations.containsValue(changed.getName()))) continue;
            for (var cfg : inv.configurations.entrySet()) {
                Object receiver = factory.getClass().getMethod("getSingleton", String.class).invoke(factory, cfg.getKey());
                if (receiver == null || !changed.isAssignableFrom(receiver.getClass())
                        || !receiver.getClass().getName().equals(cfg.getValue())) continue;
                if (lite(receiver.getClass())) out.add(new Scope(context, factory, cfg.getKey(), receiver, inv));
            }
        }
        return out;
    }

    private static boolean lite(Class<?> type) throws Exception {
        for (var a : type.getDeclaredAnnotations()) if (a.annotationType().getName().equals(CONFIG))
            return Boolean.FALSE.equals(a.annotationType().getMethod("proxyBeanMethods").invoke(a));
        return false;
    }

    private static byte[] bytes(Scope scope, Class<?> type, Map<Class<?>,byte[]> edits) {
        byte[] current = edits.get(type);
        if (current != null) return current;
        byte[] previous = scope.inventory().savedHierarchy.get(type.getName());
        if (previous != null) return previous;
        byte[] disk = SpringConfigurationOwners.fileBytes(type);
        return disk != null && com.onurkat.reclazz.transform.AdmittedBytes.matches(type, disk) ? disk : null;
    }
    private static ClassNode read(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
    private static List<Class<?>> hierarchy(Class<?> owner) {
        var result = new LinkedHashSet<Class<?>>();
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            result.add(c);
            for (Class<?> iface : c.getInterfaces()) addInterface(iface, result);
        }
        return List.copyOf(result);
    }
    private static void addInterface(Class<?> iface, Set<Class<?>> result) {
        if (result.add(iface)) for (var parent : iface.getInterfaces()) addInterface(parent, result);
    }
    private static boolean bean(MethodNode m) {
        return m.visibleAnnotations != null && m.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(BEAN));
    }
    private static String sig(MethodNode m) { return m.name + ":" + m.desc; }

    // Perform cheap relevance tests before enforcing any inheritance restriction on unrelated saves.
    static boolean relevant(Scope scope, Map<Class<?>,byte[]> edits, boolean owned) {
        Class<?> owner = scope.receiver().getClass();
        var types = hierarchy(owner);
        var origin = scope.inventory().baseline.get(owner.getName());
        if (origin == null) return false;
        if (owned) return true;
        byte[] ownerBytes = edits.get(owner);
        if (ownerBytes != null && !new HashSet<>(read(ownerBytes).interfaces).equals(new HashSet<>(origin.interfaces()))) return true;
        if (types.size() == 1) return false;
        for (var type : types) {
            byte[] b = edits.get(type);
            if (b == null) continue;
            var first = scope.inventory().baseline.get(type.getName());
            for (var method : read(b).methods) if (bean(method)
                    && (first == null || !first.factories().contains(sig(method)))) return true;
        }
        return false;
    }

    static List<Selection> select(Scope scope, Map<Class<?>,byte[]> edits) throws Exception {
        Class<?> owner = scope.receiver().getClass();
        List<Class<?>> types = hierarchy(owner);
        var selected = new LinkedHashMap<String,Selection>();
        var firstOwner = scope.inventory().baseline.get(owner.getName());
        var startupInterfaces = new HashSet<>(firstOwner.interfaces());
        for (var type : types) {
            if (isInfrastructure(type)) throw refusal("raw configuration is Spring infrastructure", type);
            byte[] saved = bytes(scope, type, edits);
            if (saved == null) {
                // The next compiler output may already be on disk. An unedited, witnessed startup
                // class contributes no new factories until its own reload has been processed.
                if (scope.inventory().baseline.containsKey(type.getName())) continue;
                throw refusal("library or unavailable configuration ancestor", type);
            }
            var node = read(saved);
            var actualInterfaces = new HashSet<String>();
            for (var iface : type.getInterfaces()) actualInterfaces.add(iface.getName().replace('.', '/'));
            if (!actualInterfaces.equals(new HashSet<>(node.interfaces)))
                throw refusal("live configuration interfaces differ from the saved declaration", type);
            String physicalSuper = type.isInterface() ? "java/lang/Object" : type.getSuperclass().getName().replace('.', '/');
            if (!Objects.equals(physicalSuper, node.superName)) throw refusal("live configuration superclass differs", type);
            var baseline = scope.inventory().baseline.get(type.getName());
            if (baseline != null && !baseline.creation().equals(CreationBaseline.digest(saved)))
                throw refusal("configuration creation state changed since startup", type);
            if (baseline != null && !actualInterfaces.containsAll(baseline.interfaces()))
                throw refusal("a startup configuration interface was removed", type);
            boolean newInterface = type.isInterface()
                    && Arrays.asList(owner.getInterfaces()).contains(type) && !startupInterfaces.contains(node.name);
            if (baseline == null && !newInterface) throw refusal("unwitnessed configuration ancestor", type);
            if (newInterface && (type.getDeclaredMethods().length == 0
                    || Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> !m.isDefault() || !annotatedBean(m))))
                throw refusal("new configuration interface contains non-factory callbacks", type);

            var signatures = new LinkedHashSet<String>();
            for (var m : node.methods) if (bean(m) && (m.access & org.objectweb.asm.Opcodes.ACC_BRIDGE) == 0
                    && (newInterface || !baseline.factories().contains(sig(m)))) signatures.add(sig(m));
            if (signatures.isEmpty()) continue;
            if (type != owner && !type.isInterface()) {
                String parsed = scope.inventory().superclasses.get(type.getName());
                var names = scope.inventory().configurations.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(parsed)).map(Map.Entry::getKey).toList();
                if (names.size() != 1) throw refusal("native configuration owner unavailable for superclass", type);
                Object singleton = scope.factory().getClass().getMethod("getSingleton", String.class)
                        .invoke(scope.factory(), names.get(0));
                if (singleton == null || !singleton.getClass().getName().equals(parsed) || !lite(singleton.getClass()))
                    throw refusal("native configuration owner is not a live raw singleton", type);
                if (scope.receiver() != singleton) continue;
            }
            var plan = AddedBeanAdapter.inspect(saved, signatures, true);
            if (!plan.refused().isEmpty()) throw new IllegalStateException(String.join("; ", plan.refused()));
            for (var factory : plan.factories()) {
                var m = factory.method();
                if (Modifier.isStatic(m.access) || (m.signature != null && m.signature.contains(")T")))
                    throw refusal("static or type-variable inherited factory requires separate native typing", type);
                var descriptor = MethodType.fromMethodDescriptorString(m.desc, type.getClassLoader());
                Method resolved = type.getDeclaredMethod(m.name, descriptor.parameterArray());
                if (!resolved.getReturnType().equals(descriptor.returnType()))
                    throw refusal("factory bridge return type differs from the selected declaration", type);
                if (type.isInterface() && !resolved.isDefault())
                    throw refusal("interface factory must be an invocable default method", type);
                var prior = selected.putIfAbsent(factory.name(), new Selection(type, resolved, factory, saved));
                if (prior != null && (!prior.resolved().getName().equals(m.name)
                        || !Arrays.equals(prior.resolved().getParameterTypes(), resolved.getParameterTypes())
                        || !type.isAssignableFrom(prior.declaring())))
                    throw refusal("overlapping factory names in the configuration hierarchy", type);
            }
        }
        return List.copyOf(selected.values());
    }

    private static boolean annotatedBean(Method m) {
        return Arrays.stream(m.getDeclaredAnnotations()).anyMatch(a -> a.annotationType().getName().equals(
                "org.springframework.context.annotation.Bean"));
    }
    private static boolean isInfrastructure(Class<?> type) {
        for (String name : List.of("org.springframework.beans.factory.FactoryBean",
                "org.springframework.beans.factory.config.BeanPostProcessor",
                "org.springframework.beans.factory.config.BeanFactoryPostProcessor"))
            try { if (Class.forName(name, false, type.getClassLoader()).isAssignableFrom(type)) return true; }
            catch (ClassNotFoundException absent) { /* not on this class path */ }
        return false;
    }
    private static IllegalStateException refusal(String reason, Class<?> type) {
        return new IllegalStateException(reason + ": " + type.getName() + "; restart required");
    }
}
