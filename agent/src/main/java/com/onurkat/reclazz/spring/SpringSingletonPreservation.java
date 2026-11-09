/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Method;
import java.util.*;

/** Cuts only edges into preserved singleton boundaries; restores Spring's bookkeeping in finally. */
final class SpringSingletonPreservation implements AutoCloseable {
    private record Edge(String dependency, String dependent) { }
    private final Object factory;
    private final Method register;
    private final List<Edge> edges;

    private SpringSingletonPreservation(Object factory, Method register, List<Edge> edges) {
        this.factory = factory; this.register = register; this.edges = edges;
    }

    static String canonical(Object factory, String name) throws Exception {
        return (String) factory.getClass().getMethod("canonicalName", String.class).invoke(factory, name);
    }

    @SuppressWarnings("unchecked")
    static SpringSingletonPreservation detach(Object factory, String root, Set<String> preserved) throws Exception {
        if (preserved.isEmpty()) return new SpringSingletonPreservation(factory, null, List.of());
        Object forward = Reflect.readField(factory, "dependentBeanMap");
        Object reverse = Reflect.readField(factory, "dependenciesForBeanMap");
        if (!(forward instanceof Map<?, ?>) || !(reverse instanceof Map<?, ?>))
            throw new IllegalStateException("Singleton dependency preservation unavailable; restart required");
        var dependents = (Map<String, Set<String>>) forward;
        var dependencies = (Map<String, Set<String>>) reverse;
        Method register = factory.getClass().getMethod("registerDependentBean", String.class, String.class);
        Set<String> names = new HashSet<>();
        for (String name : preserved) names.add(canonical(factory, name));
        List<Edge> cut = new ArrayList<>();
        synchronized (dependents) {
            Deque<String> todo = new ArrayDeque<>(); todo.add(canonical(factory, root));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String name = todo.removeFirst();
                if (!seen.add(name)) continue;
                for (String child : dependents.getOrDefault(name, Set.of())) {
                    if (names.contains(canonical(factory, child))) cut.add(new Edge(name, child));
                    else todo.add(child);
                }
            }
            // All discovery/shape checks precede the first mutation.
            for (Edge edge : cut) {
                Set<String> children = dependents.get(edge.dependency);
                children.remove(edge.dependent);
                if (children.isEmpty()) dependents.remove(edge.dependency);
            }
        }
        synchronized (dependencies) {
            for (Edge edge : cut) {
                Set<String> parents = dependencies.get(edge.dependent);
                if (parents != null) {
                    parents.remove(edge.dependency);
                    if (parents.isEmpty()) dependencies.remove(edge.dependent);
                }
            }
        }
        return new SpringSingletonPreservation(factory, register, cut);
    }

    @Override public void close() throws Exception {
        for (Edge edge : edges) register.invoke(factory, edge.dependency, edge.dependent);
    }
}
