/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.SpringXmlAliases;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.util.Reflect;
import java.nio.file.Path;
import java.util.*;

final class XmlAliasPublication {
    private XmlAliasPublication() { }

    private record Binding(Object factory, Map<?, ?> aliases, Object singleton) { }

    static void apply(Path path, List<SpringXmlReloader.OwnerChange> owners, int captured, List<Object> contexts) {
        Set<String> names = new TreeSet<>();
        owners.forEach(owner -> names.addAll(owner.diff.addedAliases.keySet()));
        boolean complete = captured == owners.size() && owners.stream().allMatch(o -> o.observed
                && o.diff.unsafe.stream().allMatch(u -> u.beanName.startsWith("alias '")));
        for (String name : names) {
            var installed = new ArrayList<Binding>();
            String target = owners.stream().map(o -> o.diff.addedAliases.get(name)).filter(Objects::nonNull)
                    .findFirst().orElseThrow();
            try {
                require(complete, "owner set or definition save incomplete");
                require(!target.equals(name), "alias cycle");
                var bindings = new ArrayList<Binding>();
                var baselines = new ArrayList<Object>();
                Set<Object> checked = Collections.newSetFromMap(new IdentityHashMap<>());
                Set<Object> publishing = Collections.newSetFromMap(new IdentityHashMap<>());
                owners.forEach(o -> publishing.add(o.factory));
                for (var owner : owners) {
                    owner.activate();
                    require(target.equals(owner.diff.addedAliases.get(name)), "declarations differ across owners");
                    Object factory = owner.factory;
                    for (Object relevant : hierarchy(factory, contexts)) {
                        if (!checked.add(relevant)) continue;
                        unbound(relevant, name);
                        // Ancestors cannot resolve aliases installed only in a child. Keep
                        // explicit name/collision checks there; dynamic lookups matter in
                        // every factory which can actually resolve a published name.
                        boolean visible = ancestors(relevant).stream().anyMatch(publishing::contains);
                        XmlAliasReferences.check(relevant, name, visible);
                    }
                    require(truth(factory, "containsBeanDefinition", target), "target has no local definition");
                    Object singleton = call(factory, "getSingleton", target);
                    require(singleton != null && !truth(factory, "isFactoryBean", target),
                            "target has no established singleton identity");
                    Object map = Reflect.readField(factory, "aliasMap");
                    require(map instanceof Map<?, ?>, "alias registry unavailable");
                    bindings.add(new Binding(factory, (Map<?, ?>)map, singleton));
                    baselines.add(SpringXmlAliases.snapshot(factory, path) != null ? factory : owner.context);
                }
                for (int i = 0; i < owners.size(); i++) {
                    owners.get(i).activate();
                    Binding b = bindings.get(i);
                    synchronized (b.aliases) {
                        unbound(b.factory, name);
                        installed.add(b);
                        call(b.factory, "registerAlias", target, name);
                    }
                }
                for (int i = 0; i < bindings.size(); i++) {
                    owners.get(i).activate();
                    Binding b = bindings.get(i);
                    require(target.equals(b.aliases.get(name)) && !truth(b.factory, "containsBeanDefinition", name)
                            && !truth(b.factory, "containsSingleton", name)
                            && call(b.factory, "getBean", name) == b.singleton
                            && call(b.factory, "getBean", target) == b.singleton, "registry changed during publication");
                    clearCaches(b.factory);
                }
                // An ancestor or child can acquire its own local binding after the initial scan.
                for (Object factory : checked) if (!publishing.contains(factory))
                    require(!truth(factory, "isAlias", name) && !truth(factory, "containsSingleton", name)
                                    && !truth(factory, "containsBeanDefinition", name),
                            "competing hierarchy binding for '" + name + "'");
                SpringXmlAliases.published(baselines, path, name, target);
                owners.forEach(o -> o.applied++);
                StatusReporter.info("alias '" + name + "' -> '" + target + "' registered live; assumes no concurrent Spring registry writes or application-cached missing lookups");
            } catch (Exception | LinkageError failure) {
                String reason = SpringReflection.rootCause(failure);
                for (int i = installed.size() - 1; i >= 0; i--) {
                    Binding b = installed.get(i);
                    try {
                        owners.get(i).activate();
                        synchronized (b.aliases) {
                            if (target.equals(b.aliases.get(name))) {
                                call(b.factory, "removeAlias", name);
                                clearCaches(b.factory);
                            }
                        }
                    } catch (Exception | LinkageError rollback) {
                        reason += "; rollback failed: " + SpringReflection.rootCause(rollback);
                    }
                }
                for (var o : owners) o.diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange("alias '" + name + "'",
                        reason + "; restart required"));
            }
        }
    }

    private static Set<Object> hierarchy(Object owner, List<Object> contexts) throws Exception {
        Set<Object> result = ancestors(owner);
        for (Object context : contexts) {
            Object factory = SpringReflection.getBeanFactory(context);
            if (factory != null && ancestors(factory).contains(owner)) result.add(factory);
        }
        return result;
    }

    private static Set<Object> ancestors(Object factory) throws Exception {
        Set<Object> chain = Collections.newSetFromMap(new IdentityHashMap<>());
        while (factory != null && chain.add(factory)) {
            Object parent = call(factory, "getParentBeanFactory");
            if (parent != null && Reflect.findMethod(parent.getClass(), "getBeanFactory") != null)
                parent = call(parent, "getBeanFactory");
            factory = parent;
        }
        return chain;
    }

    private static void clearCaches(Object factory) throws Exception {
        call(factory, "clearMetadataCache");
        call(factory, "clearByTypeCache");
    }

    private static void unbound(Object factory, String name) throws Exception {
        require(!truth(factory, "containsBeanDefinition", name) && !truth(factory, "containsSingleton", name)
                && !truth(factory, "isAlias", name) && !truth(factory, "containsBean", name), "name already bound");
    }

    private static boolean truth(Object object, String method, String name) throws Exception {
        return (boolean) call(object, method, name);
    }

    static Object call(Object object, String method, String... args) throws ReflectiveOperationException {
        Class<?>[] types = new Class<?>[args.length];
        Arrays.fill(types, String.class);
        var target = Reflect.findMethod(object.getClass(), method, types);
        if (target == null) throw new NoSuchMethodException(method);
        return target.invoke(object, (Object[])args);
    }

    static void require(boolean allowed, String reason) {
        if (!allowed) throw new IllegalStateException(reason);
    }
}
