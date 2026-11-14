/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.util.*;
import java.util.function.Predicate;

/** Immutable decisions from one precheck; stores no bean instances. */
final class ValueSelection {
    private record Context(Map<String, String> changed, Set<String> expressions) { }
    private final UUID transaction = UUID.randomUUID();
    private final IdentityHashMap<Object, Context> contexts;
    private ValueSelection(IdentityHashMap<Object, Context> contexts) { this.contexts = contexts; }

    Predicate<String> forContext(Object context, Map<String, String> changed) {
        Context selected = contexts.get(context);
        if (selected == null || !selected.changed().equals(changed))
            throw new IllegalStateException("Missing or foreign @Value selection for transaction " + transaction);
        return selected.expressions()::contains;
    }

    static final class Builder {
        private final IdentityHashMap<Object, Map<String, String>> changes = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Set<String>> expressions = new IdentityHashMap<>();
        void context(Object context, Map<String, String> changed) {
            changes.put(context, Map.copyOf(changed)); expressions.put(context, new HashSet<>());
        }
        void expression(Object context, String expression) { expressions.get(context).add(expression); }
        ValueSelection build() {
            var result = new IdentityHashMap<Object, Context>();
            changes.forEach((context, changed) -> result.put(context,
                    new Context(changed, Set.copyOf(expressions.get(context)))));
            return new ValueSelection(result);
        }
    }
}
