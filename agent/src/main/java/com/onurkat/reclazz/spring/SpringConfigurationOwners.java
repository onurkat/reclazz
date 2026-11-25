/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.transform.AdmittedBytes;
import com.onurkat.reclazz.transform.CreationBaseline;
import com.onurkat.reclazz.util.Reflect;
import java.io.IOException;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** The parser's decisions and the creation state of the raw singletons they produced. */
public final class SpringConfigurationOwners {
    private SpringConfigurationOwners() { }
    private static final Map<Object,Inventory> registries = new WeakHashMap<>();

    static final class Inventory {
        boolean refreshed;
        boolean refusalOnly;
        final Map<String,String> configurations = new LinkedHashMap<>();
        final Map<String,String> superclasses = new HashMap<>();
        // Only edited hierarchy classes need retained bytes. Startup classes use their witnessed baseline.
        final Map<String,byte[]> savedHierarchy = new HashMap<>();
        final Map<String,Baseline> baseline = new HashMap<>();
    }

    record Baseline(String creation, List<String> interfaces, Set<String> factories) { }
    static synchronized Inventory inventory(Object factory) { return registries.get(factory); }

    // The parser is a local variable of processConfigBeanDefinitions. Capture its final
    // choices while it is still reachable, without keeping Spring objects in the values.
    public static void parsed(Object parser) { parsed(parser, false); }
    public static void parsedForRefusal(Object parser) { parsed(parser, true); }

    private static void parsed(Object parser, boolean refusalOnly) {
        try {
            Object registry = Reflect.readField(parser, "registry");
            if (registry == null) return;
            var owners = new Inventory();
            owners.refusalOnly = refusalOnly;
            Map<?,?> configurations = (Map<?,?>) Reflect.readField(parser, "configurationClasses");
            for (Object configuration : configurations.values()) {
                String bean = (String) invoke(configuration, "getBeanName");
                if (bean != null) owners.configurations.put(bean, className(configuration));
            }
            if (!refusalOnly) {
                Map<?,?> supers = (Map<?,?>) Reflect.readField(parser, "knownSuperclasses");
                for (var entry : supers.entrySet())
                    owners.superclasses.put((String) entry.getKey(), className(entry.getValue()));
            }
            synchronized (SpringConfigurationOwners.class) { registries.put(registry, owners); }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Spring startup must not depend on agent bookkeeping.
        }
    }

    // Imported and member configuration names are assigned by the native reader after parsing.
    public static synchronized void definitionsLoaded(Object reader, Set<?> configurations) {
        try {
            var inventory = registries.get(Reflect.readField(reader, "registry"));
            if (inventory == null || inventory.refreshed) return;
            for (Object configuration : configurations) {
                String name = (String) invoke(configuration, "getBeanName");
                if (name != null) inventory.configurations.put(name, className(configuration));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // A missing native owner cannot participate in a later save.
        }
    }

    private static String className(Object configuration) throws ReflectiveOperationException {
        return (String) invoke(invoke(configuration, "getMetadata"), "getClassName");
    }
    private static Object invoke(Object receiver, String name) throws ReflectiveOperationException {
        var method = Reflect.findMethod(receiver.getClass(), name);
        if (method == null) throw new NoSuchMethodException(name);
        return method.invoke(receiver);
    }

    public static void refreshed(Object context) {
        try {
            Object factory = SpringBeans.getBeanFactory(context);
            var parsed = inventory(factory);
            if (parsed == null || parsed.refreshed) return;
            var inventory = new Inventory();
            inventory.refusalOnly = parsed.refusalOnly;
            inventory.configurations.putAll(parsed.configurations);
            inventory.superclasses.putAll(parsed.superclasses);
            var singleton = factory.getClass().getMethod("getSingleton", String.class);
            for (var entry : inventory.configurations.entrySet()) {
                Object bean = singleton.invoke(factory, entry.getKey());
                if (bean == null || !bean.getClass().getName().equals(entry.getValue())) continue;
                capture(inventory, bean.getClass());
            }
            inventory.refreshed = true;
            synchronized (SpringConfigurationOwners.class) {
                if (registries.get(factory) == parsed) registries.put(factory, inventory);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // An incomplete baseline refuses publication for just the affected hierarchy.
        }
    }

    private static void capture(Inventory inventory, Class<?> type) {
        if (type == null || type == Object.class || inventory.baseline.containsKey(type.getName())) return;
        byte[] bytes = fileBytes(type);
        if (bytes == null || !AdmittedBytes.matches(type, bytes)) return;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var declarations = new HashSet<String>();
        for (var method : node.methods)
            if (method.visibleAnnotations != null && method.visibleAnnotations.stream().anyMatch(
                    a -> a.desc.equals("Lorg/springframework/context/annotation/Bean;")))
                declarations.add(method.name + ":" + method.desc);
        inventory.baseline.put(type.getName(), new Baseline(CreationBaseline.digest(bytes),
                List.copyOf(node.interfaces), Set.copyOf(declarations)));
        capture(inventory, type.getSuperclass());
        for (Class<?> iface : type.getInterfaces()) capture(inventory, iface);
    }

    static byte[] fileBytes(Class<?> type) {
        var resource = type.getResource("/" + type.getName().replace('.', '/') + ".class");
        if (resource == null || !"file".equals(resource.getProtocol())) return null;
        try {
            var uri = resource.toURI();
            if (uri.getAuthority() != null) return null;
            var path = java.nio.file.Path.of(uri);
            if (path.toString().startsWith("\\\\") || uri.getPath().startsWith("//")) return null;
            return java.nio.file.Files.readAllBytes(path);
        }
        catch (IOException | java.net.URISyntaxException | IllegalArgumentException | SecurityException unavailable) { return null; }
    }
}
