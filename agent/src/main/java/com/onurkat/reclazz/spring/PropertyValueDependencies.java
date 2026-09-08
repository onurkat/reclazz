/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Tracks the placeholder reads needed to select @Value targets for one candidate. */
final class PropertyValueDependencies implements Predicate<String> {
    private static final int MAX_TEXT = 8192;
    private static final int MAX_PLACEHOLDERS = 128;
    private static final int MAX_LOOKUPS = 256;
    private static final int MAX_CACHE = 1024;
    private final Map<String, String> changed;
    private final Map<String, Boolean> matches = new HashMap<>();
    private final Object helper;
    private final Object rawResolver;
    private final Method replace;
    private final Method rawLookup;
    private final Class<?> resolverType;

    PropertyValueDependencies(Object context, Map<String, String> changed) throws Exception {
        this.changed = Map.copyOf(changed);
        ClassLoader loader = context.getClass().getClassLoader();
        Object environment = PropertyChangeCheck.candidateEnvironment(loader,
                PropertyChangeCheck.call(context, "getEnvironment"), changed);
        Class<?> sourcesType = Class.forName("org.springframework.core.env.PropertySources", false, loader);
        Class<?> rawType = Class.forName("org.springframework.core.env.PropertySourcesPropertyResolver", true, loader);
        rawResolver = rawType.getConstructor(sourcesType)
                .newInstance(PropertyChangeCheck.call(environment, "getPropertySources"));
        rawLookup = rawType.getDeclaredMethod("getPropertyAsRawString", String.class);
        rawLookup.setAccessible(true);
        Class<?> helperType = Class.forName("org.springframework.util.PropertyPlaceholderHelper", true, loader);
        resolverType = Class.forName("org.springframework.util.PropertyPlaceholderHelper$PlaceholderResolver", false, loader);
        helper = helperType.getConstructor(String.class, String.class, String.class, boolean.class)
                .newInstance("${", "}", ":", true);
        replace = helperType.getMethod("replacePlaceholders", String.class, resolverType);
    }

    @Override public boolean test(String expression) {
        if (changed.isEmpty() || expression == null || !expression.contains("${")) return false;
        // Keep direct selection cheap and preserve its existing conservative policy.
        if (SpringPropertyRebinder.referencesChangedKey(expression, changed)) return true;
        Boolean previous = matches.get(expression);
        if (previous != null) return previous;
        Budget budget = new Budget();
        boolean affected;
        try {
            budget.text(expression);
            Object recording = Proxy.newProxyInstance(resolverType.getClassLoader(), new Class<?>[]{resolverType},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("resolvePlaceholder"))
                            throw new UnsupportedOperationException(method.getName());
                        if (++budget.lookups > MAX_LOOKUPS)
                            throw new IllegalStateException("placeholder lookup limit exceeded");
                        String key = (String) args[0];
                        // Once a changed key is read, selection is proven. Do not
                        // expand its candidate value here: the existing precheck
                        // will resolve and validate the selected target.
                        if (changed.containsKey(key)) throw new Affected();
                        String raw = (String) rawLookup.invoke(rawResolver, key);
                        if (raw != null) budget.text(raw);
                        return raw;
                    });
            replace.invoke(helper, expression, recording);
            affected = false;
        } catch (Throwable failure) {
            Throwable cause = PropertyChangeCheck.unwrap(failure);
            if (cause instanceof Affected) affected = true;
            else throw new IllegalStateException("@Value dependency selection could not be verified "
                    + "(circular dependency, unreadable source, or trace limit)", cause);
        }
        if (matches.size() < MAX_CACHE) matches.put(expression, affected);
        return affected;
    }

    private static final class Affected extends RuntimeException {
        Affected() { super(null, null, false, false); }
    }

    /** Count nested syntax before Spring recurses into it, and across raw values. */
    private static final class Budget {
        int lookups;
        int placeholders;
        void text(String value) {
            if (value.length() > MAX_TEXT) throw new IllegalStateException("placeholder text limit exceeded");
            for (int at = value.indexOf("${"); at >= 0; at = value.indexOf("${", at + 2))
                if (++placeholders > MAX_PLACEHOLDERS)
                    throw new IllegalStateException("placeholder traversal limit exceeded");
        }
    }
}
