/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static com.onurkat.reclazz.spring.PropertyChangeCheck.call;

/** Replaces verified Boot file sources in place; never elevates them above command-line overrides. */
public final class SpringPropertyFiles {
    private static final String ORIGIN_SOURCE = "org.springframework.boot.env.OriginTrackedMapPropertySource";
    private static final String PROFILE = "spring.config.activate.on-profile";
    private static final Pattern DOCUMENT = Pattern.compile(".* \\(document #(\\d+)\\)$");
    // Values do not retain their Environment key. Empty sources need explicit ownership
    // because they have no remaining key from which Boot can return a resource origin.
    private final Map<Object, Map<Path, List<Owned>>> ownership = new WeakHashMap<>();
    private record Owned(String name, int document, Object source, Map<String, Object> accepted) { }
    private record Prepared(Object context, Object live, Object candidate, Path file, List<Owned> old,
                            List<Owned> next, Map<String, String> changed, Set<String> removed) { }
    public record Result(PropertyChangeOutcome outcome, Map<String, String> effective, Set<String> changed) { }

    /** Null means an unowned .properties file: the caller may retain its legacy path. */
    public synchronized Result apply(Path path, List<Object> contexts, Consumer<Runnable> boundary) {
        return apply(path, () -> contexts, boundary);
    }

    public synchronized Result apply(Path path, Supplier<List<Object>> contexts, Consumer<Runnable> boundary) {
        List<Prepared> plans = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        try {
            Path file = path.toFile().getCanonicalFile().toPath();
            byte[] bytes = Files.notExists(file) ? new byte[0] : Files.readAllBytes(file);
            boolean bootSources = false;
            for (Object context : contexts.get()) {
                if (Arrays.stream(context.getClass().getMethods()).noneMatch(m -> m.getName().equals("getEnvironment")
                        && m.getParameterCount() == 0)) continue;
                Object live = call(context, "getEnvironment");
                for (Object source : (Iterable<?>) call(live, "getPropertySources"))
                    if (source.getClass().getName().equals(ORIGIN_SOURCE)) bootSources = true;
                List<Owned> owned = owned(live, file);
                if (owned.isEmpty()) continue;
                Prepared plan = prepare(context, live, file, bytes, owned);
                plans.add(plan); changed.addAll(plan.changed().keySet());
            }
            if (plans.isEmpty()) {
                if (!bootSources && path.toString().endsWith(".properties")) return null;
                return held(PropertyChangeOutcome.State.UNCHECKABLE, "No verified Boot source owns " + path.getFileName());
            }
            Map<Prepared, Map<String, Object>> resets = new IdentityHashMap<>();
            for (Prepared plan : plans) {
                Map<String, Object> reset = new HashMap<>(); resets.put(plan, reset);
                var check = new PropertyChangeCheck().check(List.of(plan.context()), plan.changed(), plan.candidate(),
                        plan.removed(), reset);
                if (!check.passed()) return held(check.state(), String.join("; ", check.findings()));
            }
            Map<String, String> effective = new LinkedHashMap<>();
            for (String key : changed) {
                Object value = call(plans.get(0).candidate(), "getProperty", key);
                if (value != null) effective.put(key, String.valueOf(value));
            }
            PropertyChangeOutcome[] outcome = {PropertyChangeOutcome.held(PropertyChangeOutcome.State.NOT_RUN,
                    List.of("Configuration file boundary did not run"))};
            boundary.accept(() -> {
                List<String> rebound = new ArrayList<>(), rebuilt = new ArrayList<>(), failures = new ArrayList<>();
                int fields = 0;
                try {
                    // Check every source before replacing any of them.
                    for (Prepared plan : plans) verifyOwnership(plan.live(), plan.old());
                    for (Prepared plan : plans) {
                        Object sources = call(plan.live(), "getPropertySources");
                        for (Owned next : plan.next()) call(sources, "replace", next.name(), next.source());
                        // Keep the old accepted values until binding succeeds, so partial failures retry.
                        List<Owned> pending = new ArrayList<>();
                        for (int i = 0; i < plan.next().size(); i++) {
                            Owned next = plan.next().get(i);
                            pending.add(new Owned(next.name(), next.document(), next.source(), plan.old().get(i).accepted()));
                        }
                        ownership.get(plan.live()).put(plan.file(), List.copyOf(pending));
                    }
                    for (Prepared plan : plans) {
                        if (plan.changed().isEmpty()) continue;
                        var result = new SpringPropertyRebinder(List.of(plan.context()))
                                .applyLive(plan.changed(), resets.get(plan), false);
                        rebound.addAll(result.rebound()); rebuilt.addAll(result.rebuilt());
                        fields += result.valueFields(); failures.addAll(result.findings());
                    }
                    if (failures.isEmpty())
                        for (Prepared plan : plans) ownership.get(plan.live()).put(plan.file(), plan.next());
                } catch (Throwable failure) { failures.add(Failures.describe(failure)); }
                outcome[0] = new PropertyChangeOutcome(failures.isEmpty() ? PropertyChangeOutcome.State.APPLIED
                        : PropertyChangeOutcome.State.PARTIAL, rebound, fields, rebuilt, failures);
            });
            if (outcome[0].state() == PropertyChangeOutcome.State.PARTIAL)
                StatusReporter.warn("Property change partially applied; file remains pending: " + outcome[0].findings());
            return new Result(outcome[0], Map.copyOf(effective), Set.copyOf(changed));
        } catch (Throwable failure) {
            return held(PropertyChangeOutcome.State.UNCHECKABLE, Failures.describe(PropertyChangeCheck.unwrap(failure)));
        }
    }

    private static Result held(PropertyChangeOutcome.State state, String reason) {
        if (reason.toLowerCase(Locale.ROOT).contains("restart"))
            RestartLedger.note("Spring configuration", reason);
        StatusReporter.warn((state == PropertyChangeOutcome.State.REJECTED ? "Rejected" : "Uncheckable")
                + ": the running configuration is unchanged. " + reason);
        return new Result(PropertyChangeOutcome.held(state, List.of(reason)), Map.of(), Set.of());
    }

    private List<Owned> owned(Object environment, Path file) throws Exception {
        Map<Path, List<Owned>> files = ownership.computeIfAbsent(environment, ignored -> new HashMap<>());
        List<Owned> known = files.get(file);
        if (known != null) { verifyOwnership(environment, known); return known; }
        List<Owned> found = new ArrayList<>();
        for (Object source : (Iterable<?>) call(environment, "getPropertySources")) {
            if (!source.getClass().getName().equals(ORIGIN_SOURCE)) continue;
            Set<Path> origins = new HashSet<>();
            for (String key : (String[]) call(source, "getPropertyNames")) {
                Object origin = call(source, "getOrigin", key);
                if (origin == null || !origin.getClass().getName().equals("org.springframework.boot.origin.TextResourceOrigin")) continue;
                Object resource = call(origin, "getResource");
                try {
                    java.io.File originFile = (java.io.File) call(resource, "getFile");
                    origins.add(originFile.getCanonicalFile().toPath());
                } catch (java.lang.reflect.InvocationTargetException notFile) { /* Non-file resource cannot own this edit. */ }
            }
            if (origins.contains(file)) {
                if (origins.size() != 1) throw new IllegalStateException("Mixed resource origins in one property source");
                String name = (String) call(source, "getName");
                var match = DOCUMENT.matcher(name);
                found.add(new Owned(name, match.matches() ? Integer.parseInt(match.group(1)) : 0,
                        source, values(source)));
            }
        }
        if (!found.isEmpty()) files.put(file, List.copyOf(found));
        return List.copyOf(found);
    }

    private static void verifyOwnership(Object environment, List<Owned> owned) throws Exception {
        Object sources = call(environment, "getPropertySources");
        for (Owned item : owned)
            if (call(sources, "get", item.name()) != item.source())
                throw new IllegalStateException("Property source was replaced externally: " + item.name());
    }

    private static Prepared prepare(Object context, Object live, Path file, byte[] bytes, List<Owned> owned) throws Exception {
        ClassLoader loader = context.getClass().getClassLoader();
        String parserName = file.toString().endsWith(".properties") ? "PropertiesPropertySourceLoader" : "YamlPropertySourceLoader";
        Object parser = Class.forName("org.springframework.boot.env." + parserName, true, loader).getConstructor().newInstance();
        Class<?> resourceType = Class.forName("org.springframework.core.io.Resource", true, loader);
        Object originalResource = Class.forName("org.springframework.core.io.FileSystemResource", true, loader)
                .getConstructor(java.io.File.class).newInstance(file.toFile());
        Object resource = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{resourceType}, (proxy, method, args) -> {
            if (method.getName().equals("getInputStream")) return new java.io.ByteArrayInputStream(bytes);
            if (method.getName().equals("exists") || method.getName().equals("isReadable")) return true;
            if (method.getName().equals("contentLength")) return (long) bytes.length;
            try { return method.invoke(originalResource, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        List<?> documents = (List<?>) call(parser, "load", "reclazz-file", resource);
        Map<Integer, Map<String, Object>> active = new LinkedHashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> values = values(documents.get(i));
            for (String key : values.keySet())
                if ((key.startsWith("spring.config.") && !key.equals(PROFILE)) || key.startsWith("spring.profiles"))
                    throw new IllegalStateException("Config imports, locations and profile changes require a restart: " + key);
            Object profile = values.get(PROFILE);
            if (profile != null) {
                String expression = String.valueOf(profile);
                if (expression.contains("${")) throw new IllegalStateException("Profile placeholders require a restart");
                Class<?> profiles = Class.forName("org.springframework.core.env.Profiles", true, loader);
                Object selector = profiles.getMethod("of", String[].class).invoke(null, (Object) new String[]{expression});
                if (!(boolean) call(live, "acceptsProfiles", selector)) continue;
            }
            active.put(i, values);
        }
        if (documents.isEmpty()) active.put(0, Map.of());
        Set<Integer> slots = new HashSet<>();
        for (Owned item : owned) slots.add(item.document());
        if (slots.size() != owned.size() || !slots.equals(active.keySet()))
            throw new IllegalStateException("Active configuration document topology changed; restart required");
        List<Owned> next = new ArrayList<>();
        Map<String, String> changed = new LinkedHashMap<>();
        Set<String> removed = new HashSet<>();
        for (Owned old : owned) {
            Map<String, Object> replacement = active.get(old.document());
            if (!Objects.equals(old.accepted().get(PROFILE), replacement.get(PROFILE)))
                throw new IllegalStateException("Configuration document activation changed; restart required");
            Set<String> keys = new HashSet<>(old.accepted().keySet()); keys.addAll(replacement.keySet());
            for (String key : keys) if (!Objects.equals(old.accepted().get(key), replacement.get(key))) {
                changed.put(key, String.valueOf(replacement.getOrDefault(key, "")));
                if (!replacement.containsKey(key)) removed.add(key);
            }
            Object source = Class.forName(ORIGIN_SOURCE, true, loader).getConstructor(String.class, Map.class)
                    .newInstance(old.name(), replacement);
            next.add(new Owned(old.name(), old.document(), source, replacement));
        }
        Object candidate = PropertyChangeCheck.candidateEnvironment(loader, live, Map.of());
        Object sources = call(candidate, "getPropertySources");
        // candidateEnvironment's empty legacy overlay does not change file precedence.
        for (Owned item : next) call(sources, "replace", item.name(), item.source());
        return new Prepared(context, live, candidate, file, owned, List.copyOf(next), Map.copyOf(changed), Set.copyOf(removed));
    }

    private static Map<String, Object> values(Object source) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : (String[]) call(source, "getPropertyNames")) {
            Object value = call(source, "getProperty", key);
            if (value != null) result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }
}
