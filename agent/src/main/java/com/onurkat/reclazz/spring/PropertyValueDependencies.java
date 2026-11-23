/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
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
    private final Trace before;
    private final Trace after;
    private record Read(String source, String key, Object value) { }
    private record Resolution(String value, List<Read> reads) { }
    private record Trace(Object resolver, Method lookup, List<Read> reads) { }


    PropertyValueDependencies(Object context, Map<String, String> changed) throws Exception {
        this(context, changed, null);
    }

    PropertyValueDependencies(Object context, Map<String, String> changed, Object preparedEnvironment) throws Exception {
        this(context, changed, preparedEnvironment, null);
    }

    PropertyValueDependencies(Object context, Map<String, String> changed, Object preparedEnvironment,
                              Object beforeEnvironment) throws Exception {
        this.changed = Map.copyOf(changed);
        ClassLoader loader = context.getClass().getClassLoader();
        Object environment = preparedEnvironment != null ? preparedEnvironment
                : PropertyChangeCheck.candidateEnvironment(loader,
                PropertyChangeCheck.call(context, "getEnvironment"), changed);
        Object live = PropertyChangeCheck.call(context, "getEnvironment");
        boolean boot = PropertyChangeCheck.bootResolver(loader, live);
        Object liveResolver = PropertyChangeCheck.environmentResolver(loader, live);
        if (!liveResolver.getClass().getName().equals("org.springframework.core.env.PropertySourcesPropertyResolver")
                && !PropertyChangeCheck.isBootResolver(liveResolver))
            throw new IllegalStateException("@Value escape policy could not be verified: unknown resolver");
        Class<?> sourcesType = Class.forName("org.springframework.core.env.PropertySources", false, loader);
        Class<?> rawType = Class.forName("org.springframework.core.env.PropertySourcesPropertyResolver", true, loader);
        rawResolver = rawType.getConstructor(sourcesType)
                .newInstance(PropertyChangeCheck.call(environment, "getPropertySources"));
        rawLookup = rawType.getDeclaredMethod("getPropertyAsRawString", String.class);
        rawLookup.setAccessible(true);
        if (boot) {
            Object old = beforeEnvironment != null ? beforeEnvironment
                    : PropertyChangeCheck.acceptedEnvironment(loader, live);
            Set<String> recorded = new LinkedHashSet<>();
            Object liveSources = PropertyChangeCheck.call(live, "getPropertySources");
            for (Object source : (Iterable<?>) PropertyChangeCheck.call(environment, "getPropertySources")) {
                String name = (String) PropertyChangeCheck.call(source, "getName");
                if (name.equals("configurationProperties")) continue;
                if (name.equals("reclazz-candidate") || source != PropertyChangeCheck.call(liveSources, "get", name))
                    recorded.add(name);
            }
            before = trace(loader, old, recorded);
            after = trace(loader, environment, recorded);
        } else { before = null; after = null; }

        Class<?> helperType = Class.forName("org.springframework.util.PropertyPlaceholderHelper", true, loader);
        resolverType = Class.forName("org.springframework.util.PropertyPlaceholderHelper$PlaceholderResolver", false, loader);
        java.lang.reflect.Constructor<?> constructor;
        try {
            constructor = helperType.getConstructor(String.class, String.class, String.class, Character.class, boolean.class);
        } catch (NoSuchMethodException legacy) {
            constructor = helperType.getConstructor(String.class, String.class, String.class, boolean.class);
        }
        if (constructor.getParameterCount() == 5) {
            Object escape = escapePolicy(loader, rawType, rawResolver,
                    PropertyChangeCheck.call(context, "getEnvironment"), environment);
            helper = constructor.newInstance("${", "}", ":", escape, true);
        } else {
            helper = constructor.newInstance("${", "}", ":", true);
        }
        replace = helperType.getMethod("replacePlaceholders", String.class, resolverType);
    }

    private static Object escapePolicy(ClassLoader loader, Class<?> rawType, Object fresh,
                                       Object live, Object candidate) throws Exception {
        try {
            Class<?> environmentType = Class.forName("org.springframework.core.env.AbstractEnvironment", false, loader);
            Class<?> resolverBase = Class.forName("org.springframework.core.env.AbstractPropertyResolver", false, loader);
            Method getter = environmentType.getDeclaredMethod("getPropertyResolver");
            getter.setAccessible(true);
            var field = resolverBase.getDeclaredField("escapeCharacter");
            if (field.getType() != Character.class) throw new IllegalStateException("unknown escape member");
            field.setAccessible(true);
            Object expected = field.get(fresh);
            for (Object environment : new Object[]{live, candidate}) {
                if (!environmentType.isInstance(environment))
                    throw new IllegalStateException("unknown environment");
                Object resolver = getter.invoke(environment);
                if (resolver == null || (resolver.getClass() != rawType && !PropertyChangeCheck.isBootResolver(resolver))
                        || !java.util.Objects.equals(expected, field.get(resolver)))
                    throw new IllegalStateException("different or unknown resolver");
            }
            return expected;
        } catch (Exception failure) {
            throw new IllegalStateException("@Value escape policy could not be verified", failure);
        }
    }

    @Override public boolean test(String expression) {
        if (changed.isEmpty() || expression == null || !expression.contains("${")) return false;
        if (before != null) {
            Boolean previous = matches.get(expression);
            if (previous != null) return previous;
            try {
                Resolution old = resolve(before, expression);
                Resolution next = resolve(after, expression);
                boolean selected = !Objects.equals(old.value(), next.value()) || !old.reads().equals(next.reads());
                if (matches.size() < MAX_CACHE) matches.put(expression, selected);
                return selected;
            } catch (Throwable failure) {
                throw new IllegalStateException("@Value dependency selection could not be verified "
                        + "(circular dependency, unreadable source, or trace limit)", PropertyChangeCheck.unwrap(failure));
            }
        }
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

    /** Clone only maps created by the candidate/file path. Retain their exact
     * source class so Boot's native adapter keeps its enumerable-source policy. */
    private static Trace trace(ClassLoader loader, Object environment, Set<String> names) throws Exception {
        List<Read> reads = new ArrayList<>();
        Object detached = Class.forName("org.springframework.core.env.StandardEnvironment", true, loader).getConstructor().newInstance();
        Object sources = PropertyChangeCheck.call(detached, "getPropertySources");
        List<String> defaults = new ArrayList<>();
        for (Object source : (Iterable<?>) sources) defaults.add((String) PropertyChangeCheck.call(source, "getName"));
        for (String name : defaults) PropertyChangeCheck.call(sources, "remove", name);
        for (Object source : (Iterable<?>) PropertyChangeCheck.call(environment, "getPropertySources"))
            if (!PropertyChangeCheck.call(source, "getName").equals("configurationProperties"))
                PropertyChangeCheck.call(sources, "addLast", source);
        for (String name : names) {
            Object source = PropertyChangeCheck.call(sources, "get", name);
            if (source == null) continue;
            String type = source.getClass().getName();
            // R38: a config tree's values are file contents the binder reads as text; a map copy of their text reads the same.
            boolean tree = type.equals("org.springframework.boot.env.ConfigTreePropertySource");
            if (!tree && !type.equals("org.springframework.core.env.MapPropertySource")
                    && !type.equals("org.springframework.boot.env.OriginTrackedMapPropertySource"))
                throw new IllegalStateException("Unverified candidate map source: " + type);
            Map<String, Object> data = new LinkedHashMap<>();
            String[] keys = (String[]) PropertyChangeCheck.call(source, "getPropertyNames");
            if (keys.length > MAX_TEXT) throw new IllegalStateException("candidate source key limit exceeded");
            for (String key : keys) {
                Object value = PropertyChangeCheck.call(source, "getProperty", key);
                data.put(key, tree && value != null ? value.toString() : value);
            }
            Map<String, Object> recording = new AbstractMap<>() {
                @Override public Set<Entry<String, Object>> entrySet() { return Collections.unmodifiableMap(data).entrySet(); }
                @Override public Object get(Object key) {
                    if (reads.size() >= MAX_LOOKUPS) throw new IllegalStateException("source read limit exceeded");
                    Object value = data.get(key);
                    reads.add(new Read(name, (String) key, value));
                    return value;
                }
            };
            Class<?> copyType = tree ? Class.forName("org.springframework.core.env.MapPropertySource", true, loader) : source.getClass();
            Object copy = copyType.getConstructor(String.class, Map.class).newInstance(name, recording);
            PropertyChangeCheck.call(sources, "replace", name, copy);
        }
        PropertyChangeCheck.attachBoot(loader, detached);
        Class<?> factory = Class.forName("org.springframework.boot.context.properties.source.ConfigurationPropertySources", true, loader);
        Object resolver = factory.getMethod("createPropertyResolver",
                Class.forName("org.springframework.core.env.MutablePropertySources", false, loader)).invoke(null, sources);
        Method lookup = resolver.getClass().getDeclaredMethod("getPropertyAsRawString", String.class);
        lookup.setAccessible(true);
        return new Trace(resolver, lookup, reads);
    }

    private Resolution resolve(Trace trace, String expression) throws Exception {
        trace.reads().clear();
        Budget budget = new Budget(); budget.text(expression);
        Object recording = Proxy.newProxyInstance(resolverType.getClassLoader(), new Class<?>[]{resolverType},
                (proxy, method, args) -> {
                    if (!method.getName().equals("resolvePlaceholder")) throw new UnsupportedOperationException(method.getName());
                    if (++budget.lookups > MAX_LOOKUPS) throw new IllegalStateException("placeholder lookup limit exceeded");
                    String raw = (String) trace.lookup().invoke(trace.resolver(), args[0]);
                    if (raw != null) budget.text(raw);
                    return raw;
                });
        String result = (String) replace.invoke(helper, expression, recording);
        return new Resolution(result, List.copyOf(trace.reads()));
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
